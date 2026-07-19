#!/usr/bin/env python3
"""Provider-neutral digest promotion and GHCR/Git-ref CAS adapters."""

from __future__ import annotations

import argparse
import base64
import copy
import json
import os
import re
import subprocess
import sys
import threading
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Mapping, Protocol, Sequence


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from release_json import canonical_bytes, parse_json_bytes  # noqa: E402


HEX64 = re.compile(r"^[0-9a-f]{64}$")
OCI_DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
OCI_URI = re.compile(r"^ghcr\.io/[a-z0-9_.-]+/[a-z0-9_./-]+@sha256:[0-9a-f]{64}$")
GHCR_TAG_URI = re.compile(
    r"^ghcr\.io/(?P<owner>[a-z0-9_.-]+)/(?P<package>[a-z0-9_.-]+):(?P<tag>[A-Za-z0-9_.-]+)$"
)
SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
RUN_ID = re.compile(r"^[0-9]+$")
LEDGER_URI = re.compile(
    r"^https://api\.github\.com/repos/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/git/ref/tags/"
    r"promotion-ledger/[0-9]+\.[0-9]+\.[0-9]+-(?:stage|production)$"
)
EVIDENCE_URI_FIELDS = (
    "artifact_uri",
    "sbom_uri",
    "attestation_uri",
    "web_uri",
    "manifest_uri",
    "signature_uri",
    "index_uri",
)
MATERIAL_RECORD_FIELDS = (
    "releaseVersion",
    "targetEnvironment",
    "artifactOciDigest",
    "targetArtifactUri",
    "manifestUri",
    "manifestSha256",
    "evidenceIndexUri",
    "evidenceIndexSha256",
    "sbomUri",
    "attestationUri",
    "webUri",
    "signatureUri",
)
TARGET_REPOSITORIES = {
    "stage": "ghcr.io/keliihall/scholarsense-release-stage",
    "production": "ghcr.io/keliihall/scholarsense-release-production",
}


class PromotionError(RuntimeError):
    """A stable fail-closed promotion diagnostic."""


@dataclass(frozen=True)
class PromotionEvidence:
    artifact_uri: str
    sbom_uri: str
    attestation_uri: str
    web_uri: str
    manifest_uri: str
    signature_uri: str
    index_uri: str
    artifact_oci_digest: str
    manifest_sha256: str
    evidence_index_sha256: str


@dataclass(frozen=True)
class PromotionRequest:
    release_version: str
    from_environment: str
    target_environment: str
    evidence: PromotionEvidence
    actor: str
    approver: str
    run_id: str
    run_attempt: int
    rollback_of_ledger_uri: str | None = None


@dataclass(frozen=True)
class VerificationReceipt:
    evidence: PromotionEvidence
    verifier_identity: str
    verified_at: datetime
    expires_at: datetime


@dataclass(frozen=True)
class StoreCopy:
    target_artifact_uri: str
    target_tag_uri: str
    tag_created: bool


@dataclass(frozen=True)
class PromotionOutcome:
    result: str
    record: dict[str, Any]
    ledger_uri: str


class EvidenceVerifierPort(Protocol):
    def verify(self, request: PromotionRequest, now: datetime) -> VerificationReceipt: ...


class DigestStorePort(Protocol):
    def prepare(self, release_version: str, target_repository: str) -> None: ...

    def copy_by_digest(self, source_uri: str, target_repository: str, target_tag: str) -> StoreCopy: ...

    def artifact_exists(self, uri: str) -> bool: ...

    def tag_digest(self, tag_uri: str) -> str | None: ...

    def delete_tag(self, tag_uri: str) -> None: ...


class ImmutableLedgerPort(Protocol):
    def uri(self, release_version: str, target_environment: str) -> str: ...

    def create(self, release_version: str, target_environment: str, record: dict[str, Any]) -> bool: ...

    def read(self, release_version: str, target_environment: str) -> dict[str, Any] | None: ...


class PromotionPort(Protocol):
    def promote(self, request: PromotionRequest, now: datetime) -> PromotionOutcome: ...

    def reconcile(self, release_version: str, target_environment: str, now: datetime) -> dict[str, Any]: ...


def _utc(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise PromotionError("PROMOTION_TIMEZONE_REQUIRED")
    return value.astimezone(timezone.utc)


def _timestamp(value: datetime) -> str:
    return _utc(value).isoformat(timespec="seconds").replace("+00:00", "Z")


def _uri_digest(uri: str) -> str | None:
    return uri.rpartition("@")[2] if OCI_URI.fullmatch(uri) else None


def _request_issues(request: PromotionRequest) -> list[str]:
    issues: list[str] = []
    if not SEMVER.fullmatch(request.release_version):
        issues.append("PROMOTION_RELEASE_VERSION_INVALID")
    expected_from = {"stage": "candidate", "production": "stage"}.get(request.target_environment)
    if expected_from is None or request.from_environment != expected_from:
        issues.append("PROMOTION_ENVIRONMENT_TRANSITION_INVALID")
    if not request.actor or not request.approver:
        issues.append("PROMOTION_PROTECTED_IDENTITY_MISSING")
    if not RUN_ID.fullmatch(request.run_id):
        issues.append("PROMOTION_RUN_ID_INVALID")
    if isinstance(request.run_attempt, bool) or request.run_attempt < 1:
        issues.append("PROMOTION_RUN_ATTEMPT_INVALID")
    values = asdict(request.evidence)
    for field in EVIDENCE_URI_FIELDS:
        if not OCI_URI.fullmatch(str(values[field])):
            issues.append(f"PROMOTION_IMMUTABLE_URI_REQUIRED: {field}")
    if not OCI_DIGEST.fullmatch(request.evidence.artifact_oci_digest):
        issues.append("PROMOTION_ARTIFACT_DIGEST_INVALID")
    if _uri_digest(request.evidence.artifact_uri) != request.evidence.artifact_oci_digest:
        issues.append("PROMOTION_ARTIFACT_URI_DIGEST_MISMATCH")
    if not HEX64.fullmatch(request.evidence.manifest_sha256):
        issues.append("PROMOTION_MANIFEST_DIGEST_INVALID")
    if not HEX64.fullmatch(request.evidence.evidence_index_sha256):
        issues.append("PROMOTION_EVIDENCE_INDEX_DIGEST_INVALID")
    if request.rollback_of_ledger_uri is not None and not LEDGER_URI.fullmatch(request.rollback_of_ledger_uri):
        issues.append("PROMOTION_ROLLBACK_LEDGER_URI_INVALID")
    return sorted(set(issues))


def _receipt_issues(request: PromotionRequest, receipt: VerificationReceipt, now: datetime) -> list[str]:
    issues: list[str] = []
    if receipt.evidence != request.evidence:
        issues.append("PROMOTION_VERIFICATION_SUBJECT_MISMATCH")
    if not receipt.verifier_identity:
        issues.append("PROMOTION_VERIFIER_IDENTITY_MISSING")
    try:
        verified = _utc(receipt.verified_at)
        expires = _utc(receipt.expires_at)
        current = _utc(now)
    except PromotionError as error:
        return [str(error)]
    if verified > current:
        issues.append("PROMOTION_VERIFICATION_FROM_FUTURE")
    if expires <= verified or expires <= current:
        issues.append("PROMOTION_EVIDENCE_EXPIRED")
    return sorted(set(issues))


def _record_material(record: Mapping[str, Any]) -> tuple[Any, ...]:
    return tuple(record.get(field) for field in MATERIAL_RECORD_FIELDS)


def _record(
    request: PromotionRequest,
    receipt: VerificationReceipt,
    copied: StoreCopy,
    ledger_uri: str,
    now: datetime,
) -> dict[str, Any]:
    evidence = request.evidence
    value: dict[str, Any] = {
        "version": "PROMOTION-RECORD-1.0.0",
        "releaseVersion": request.release_version,
        "fromEnvironment": request.from_environment,
        "targetEnvironment": request.target_environment,
        "sourceArtifactUri": evidence.artifact_uri,
        "targetArtifactUri": copied.target_artifact_uri,
        "targetTagUri": copied.target_tag_uri,
        "artifactOciDigest": evidence.artifact_oci_digest,
        "sbomUri": evidence.sbom_uri,
        "attestationUri": evidence.attestation_uri,
        "webUri": evidence.web_uri,
        "manifestUri": evidence.manifest_uri,
        "manifestSha256": evidence.manifest_sha256,
        "signatureUri": evidence.signature_uri,
        "evidenceIndexUri": evidence.index_uri,
        "evidenceIndexSha256": evidence.evidence_index_sha256,
        "verifierIdentity": receipt.verifier_identity,
        "verifiedAt": _timestamp(receipt.verified_at),
        "evidenceExpiresAt": _timestamp(receipt.expires_at),
        "actor": request.actor,
        "approver": request.approver,
        "runId": request.run_id,
        "runAttempt": request.run_attempt,
        "result": "promoted",
        "createdAt": _timestamp(now),
        "ledgerUri": ledger_uri,
    }
    if request.rollback_of_ledger_uri is not None:
        value["rollbackOfLedgerUri"] = request.rollback_of_ledger_uri
    return value


def promotion_record_issues(
    record: Any,
    release_version: str,
    target_environment: str,
    expected_ledger_uri: str,
) -> list[str]:
    if not isinstance(record, dict):
        return ["PROMOTION_LEDGER_RECORD_INVALID"]
    issues: list[str] = []
    expected = {
        "version": "PROMOTION-RECORD-1.0.0",
        "releaseVersion": release_version,
        "targetEnvironment": target_environment,
        "ledgerUri": expected_ledger_uri,
        "result": "promoted",
    }
    for field, value in expected.items():
        if record.get(field) != value:
            issues.append(f"PROMOTION_LEDGER_{field.upper()}_MISMATCH")
    digest = record.get("artifactOciDigest")
    target_uri = record.get("targetArtifactUri")
    target_tag = record.get("targetTagUri")
    target_repository = TARGET_REPOSITORIES.get(target_environment, "")
    if not isinstance(digest, str) or not OCI_DIGEST.fullmatch(digest):
        issues.append("PROMOTION_LEDGER_ARTIFACT_DIGEST_INVALID")
    if target_uri != f"{target_repository}@{digest}":
        issues.append("PROMOTION_STORE_LEDGER_DRIFT")
    if not isinstance(target_tag, str) or not target_tag.startswith(f"{target_repository}:release-"):
        issues.append("PROMOTION_TARGET_TAG_INVALID")
    for field in (
        "sourceArtifactUri", "targetArtifactUri", "sbomUri", "attestationUri", "webUri",
        "manifestUri", "signatureUri", "evidenceIndexUri",
    ):
        if not OCI_URI.fullmatch(str(record.get(field, ""))):
            issues.append(f"PROMOTION_LEDGER_IMMUTABLE_URI_REQUIRED: {field}")
    for field in ("manifestSha256", "evidenceIndexSha256"):
        if not HEX64.fullmatch(str(record.get(field, ""))):
            issues.append(f"PROMOTION_LEDGER_DIGEST_INVALID: {field}")
    rollback_uri = record.get("rollbackOfLedgerUri")
    if rollback_uri is not None and not LEDGER_URI.fullmatch(str(rollback_uri)):
        issues.append("PROMOTION_ROLLBACK_LEDGER_URI_INVALID")
    return sorted(set(issues))


class PromotionService:
    """Provider-neutral promotion orchestration over verifier/store/ledger ports."""

    def __init__(
        self,
        verifier: EvidenceVerifierPort,
        store: DigestStorePort,
        ledger: ImmutableLedgerPort,
        target_repositories: Mapping[str, str] | None = None,
    ) -> None:
        self.verifier = verifier
        self.store = store
        self.ledger = ledger
        self.target_repositories = dict(target_repositories or TARGET_REPOSITORIES)

    def target_tag_uri(self, request: PromotionRequest) -> str:
        repository = self.target_repositories[request.target_environment]
        short_digest = request.evidence.artifact_oci_digest.removeprefix("sha256:")[:16]
        return f"{repository}:release-{request.release_version}-{short_digest}"

    def _after_copy(self, _request: PromotionRequest, _copied: StoreCopy) -> None:
        return

    def promote(self, request: PromotionRequest, now: datetime) -> PromotionOutcome:
        issues = _request_issues(request)
        if issues:
            raise PromotionError(issues[0])
        receipt = self.verifier.verify(request, now)
        issues = _receipt_issues(request, receipt, now)
        if issues:
            raise PromotionError(issues[0])
        target_repository = self.target_repositories[request.target_environment]
        existing_before_copy = self.ledger.read(request.release_version, request.target_environment)
        if existing_before_copy is None:
            self.store.prepare(request.release_version, target_repository)
        tag_uri = self.target_tag_uri(request)
        copied: StoreCopy | None = None
        durable = False
        try:
            copied = self.store.copy_by_digest(request.evidence.artifact_uri, target_repository, tag_uri)
            if copied.target_tag_uri != tag_uri:
                raise PromotionError("PROMOTION_TARGET_TAG_UNEXPECTED")
            if _uri_digest(copied.target_artifact_uri) != request.evidence.artifact_oci_digest:
                raise PromotionError("PROMOTION_TARGET_DIGEST_MISMATCH")
            self._after_copy(request, copied)
            ledger_uri = self.ledger.uri(request.release_version, request.target_environment)
            candidate = _record(request, receipt, copied, ledger_uri, now)
            try:
                created = self.ledger.create(request.release_version, request.target_environment, candidate)
            except PromotionError:
                existing_after_error = self.ledger.read(request.release_version, request.target_environment)
                if existing_after_error is None:
                    raise
                created = False
            if created:
                durable = True
                return PromotionOutcome("promoted", copy.deepcopy(candidate), ledger_uri)
            existing = self.ledger.read(request.release_version, request.target_environment)
            if existing is None:
                raise PromotionError("PROMOTION_LEDGER_CAS_INDETERMINATE")
            if _record_material(existing) != _record_material(candidate):
                raise PromotionError("PROMOTION_IDEMPOTENCY_CONFLICT")
            durable = True
            replay = copy.deepcopy(existing)
            replay["result"] = "replayed"
            return PromotionOutcome("replayed", replay, ledger_uri)
        finally:
            if copied is not None and copied.tag_created and not durable:
                self.store.delete_tag(copied.target_tag_uri)

    def reconcile(self, release_version: str, target_environment: str, now: datetime) -> dict[str, Any]:
        _utc(now)
        expected_uri = self.ledger.uri(release_version, target_environment)
        record = self.ledger.read(release_version, target_environment)
        issues = promotion_record_issues(record, release_version, target_environment, expected_uri)
        if issues:
            raise PromotionError(issues[0])
        assert record is not None
        if not self.store.artifact_exists(record["targetArtifactUri"]):
            raise PromotionError("PROMOTION_TARGET_ARTIFACT_MISSING")
        tag_digest = self.store.tag_digest(record["targetTagUri"])
        if tag_digest != record["artifactOciDigest"]:
            raise PromotionError("PROMOTION_TARGET_TAG_DRIFT")
        return copy.deepcopy(record)

    def rollback(
        self,
        request: PromotionRequest,
        historical_record: Mapping[str, Any],
        now: datetime,
    ) -> PromotionOutcome:
        if request.rollback_of_ledger_uri != historical_record.get("ledgerUri"):
            raise PromotionError("PROMOTION_ROLLBACK_LEDGER_MISMATCH")
        expected = {
            "artifactOciDigest": request.evidence.artifact_oci_digest,
            "manifestUri": request.evidence.manifest_uri,
            "manifestSha256": request.evidence.manifest_sha256,
            "evidenceIndexUri": request.evidence.index_uri,
            "evidenceIndexSha256": request.evidence.evidence_index_sha256,
            "sbomUri": request.evidence.sbom_uri,
            "attestationUri": request.evidence.attestation_uri,
            "webUri": request.evidence.web_uri,
            "signatureUri": request.evidence.signature_uri,
        }
        if any(historical_record.get(field) != value for field, value in expected.items()):
            raise PromotionError("PROMOTION_ROLLBACK_MATERIAL_MISMATCH")
        return self.promote(request, now)


class _InMemoryVerifier:
    def __init__(self, identity: str, ttl: timedelta) -> None:
        self.identity = identity
        self.ttl = ttl
        self.available: dict[str, Any] = {}
        self.verification_count = 0

    def seed(self, evidence: PromotionEvidence) -> None:
        self.available.update(asdict(evidence))

    def verify(self, request: PromotionRequest, now: datetime) -> VerificationReceipt:
        self.verification_count += 1
        expected = asdict(request.evidence)
        for field, value in expected.items():
            if field not in self.available:
                raise PromotionError(f"PROMOTION_EVIDENCE_MISSING: {field}")
            if self.available[field] != value:
                raise PromotionError(f"PROMOTION_EVIDENCE_TAMPERED: {field}")
        return VerificationReceipt(request.evidence, self.identity, now, now + self.ttl)


class _InMemoryStore:
    def __init__(self) -> None:
        self.candidates: set[str] = set()
        self.target_artifacts: set[str] = set()
        self.target_tags: dict[str, str] = {}
        self._lock = threading.Lock()

    def prepare(self, _release_version: str, _target_repository: str) -> None:
        return

    def copy_by_digest(self, source_uri: str, target_repository: str, target_tag: str) -> StoreCopy:
        with self._lock:
            if source_uri not in self.candidates:
                raise PromotionError("PROMOTION_SOURCE_ARTIFACT_MISSING")
            digest = _uri_digest(source_uri)
            assert digest is not None
            previous = self.target_tags.get(target_tag)
            if previous is not None and previous != digest:
                raise PromotionError("PROMOTION_TARGET_TAG_COLLISION")
            created = previous is None
            self.target_tags[target_tag] = digest
            target_uri = f"{target_repository}@{digest}"
            self.target_artifacts.add(target_uri)
            return StoreCopy(target_uri, target_tag, created)

    def artifact_exists(self, uri: str) -> bool:
        with self._lock:
            return uri in self.target_artifacts

    def tag_digest(self, tag_uri: str) -> str | None:
        with self._lock:
            return self.target_tags.get(tag_uri)

    def delete_tag(self, tag_uri: str) -> None:
        with self._lock:
            self.target_tags.pop(tag_uri, None)


class _InMemoryLedger:
    def __init__(self) -> None:
        self.records: dict[tuple[str, str], dict[str, Any]] = {}
        self.write_count = 0
        self._lock = threading.Lock()

    def uri(self, release_version: str, target_environment: str) -> str:
        return (
            "https://api.github.com/repos/keliihall/ScholarSense-bmad-method/git/ref/tags/"
            f"promotion-ledger/{release_version}-{target_environment}"
        )

    def create(self, release_version: str, target_environment: str, record: dict[str, Any]) -> bool:
        key = (release_version, target_environment)
        with self._lock:
            if key in self.records:
                return False
            self.records[key] = copy.deepcopy(record)
            self.write_count += 1
            return True

    def read(self, release_version: str, target_environment: str) -> dict[str, Any] | None:
        with self._lock:
            value = self.records.get((release_version, target_environment))
            return copy.deepcopy(value) if value is not None else None


class InMemoryPromotionAdapter(PromotionService):
    """Deterministic fixture adapter for the provider-neutral contract."""

    def __init__(self, verifier_identity: str, receipt_ttl: timedelta) -> None:
        self._verifier = _InMemoryVerifier(verifier_identity, receipt_ttl)
        self._store = _InMemoryStore()
        self._ledger = _InMemoryLedger()
        self.fail_after_copy = False
        self.build_count = 0
        super().__init__(self._verifier, self._store, self._ledger)

    @property
    def receipt_ttl(self) -> timedelta:
        return self._verifier.ttl

    @receipt_ttl.setter
    def receipt_ttl(self, value: timedelta) -> None:
        self._verifier.ttl = value

    @property
    def verification_count(self) -> int:
        return self._verifier.verification_count

    @property
    def ledger_write_count(self) -> int:
        return self._ledger.write_count

    @property
    def ledger_records(self) -> dict[tuple[str, str], dict[str, Any]]:
        return self._ledger.records

    @property
    def target_tags(self) -> dict[str, str]:
        return self._store.target_tags

    def seed_candidate(self, uri: str) -> None:
        if not OCI_URI.fullmatch(uri):
            raise PromotionError("PROMOTION_IMMUTABLE_URI_REQUIRED: artifact_uri")
        self._store.candidates.add(uri)

    def seed_evidence(self, evidence: PromotionEvidence) -> None:
        self._verifier.seed(evidence)

    def remove_evidence(self, label: str) -> None:
        aliases = {"signature": "signature_uri", "index": "index_uri", "manifest": "manifest_uri"}
        self._verifier.available.pop(aliases.get(label, label), None)

    def tamper_evidence(self, field: str, value: Any) -> None:
        self._verifier.available[field] = value

    def _after_copy(self, _request: PromotionRequest, _copied: StoreCopy) -> None:
        if self.fail_after_copy:
            raise PromotionError("PROMOTION_INJECTED_FAILURE")


@dataclass(frozen=True)
class CommandResult:
    returncode: int
    stdout: str
    stderr: str


class CommandRunner:
    def run(
        self,
        arguments: Sequence[str],
        *,
        environment: Mapping[str, str] | None = None,
        stdin: str | None = None,
    ) -> CommandResult:
        result = subprocess.run(
            list(arguments),
            cwd=PROJECT_ROOT,
            env=dict(environment) if environment is not None else None,
            input=stdin,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        return CommandResult(result.returncode, result.stdout, result.stderr)


class OrasDigestStore:
    """Real GHCR adapter: source and durable identity are always OCI digests."""

    def __init__(self, oras: Path, runner: CommandRunner | None = None, gh: str = "gh") -> None:
        self.oras = str(oras)
        self.runner = runner or CommandRunner()
        self.gh = gh

    def _descriptor(self, uri: str) -> str | None:
        result = self.runner.run([self.oras, "manifest", "fetch", uri, "--descriptor"])
        if result.returncode != 0:
            return None
        try:
            value = json.loads(result.stdout)
            digest = value.get("digest")
        except (AttributeError, json.JSONDecodeError):
            raise PromotionError("PROMOTION_STORE_DESCRIPTOR_INVALID")
        if not isinstance(digest, str) or not OCI_DIGEST.fullmatch(digest):
            raise PromotionError("PROMOTION_STORE_DESCRIPTOR_INVALID")
        return digest

    def prepare(self, release_version: str, target_repository: str) -> None:
        if not SEMVER.fullmatch(release_version) or target_repository not in TARGET_REPOSITORIES.values():
            raise PromotionError("PROMOTION_STORE_PREPARE_INPUT_INVALID")
        listed = self.runner.run([self.oras, "repo", "tags", target_repository])
        if listed.returncode != 0:
            lowered = listed.stderr.lower()
            if any(marker in lowered for marker in ("not found", "name unknown", "name_unknown")):
                return
            raise PromotionError("PROMOTION_STORE_TAG_LIST_FAILED")
        prefix = f"release-{release_version}-"
        tags = sorted(line.strip() for line in listed.stdout.splitlines() if line.strip().startswith(prefix))
        for tag in tags:
            self.delete_tag(f"{target_repository}:{tag}")

    def copy_by_digest(self, source_uri: str, target_repository: str, target_tag: str) -> StoreCopy:
        source_digest = _uri_digest(source_uri)
        if source_digest is None:
            raise PromotionError("PROMOTION_IMMUTABLE_URI_REQUIRED: artifact_uri")
        previous = self._descriptor(target_tag)
        if previous is not None and previous != source_digest:
            raise PromotionError("PROMOTION_TARGET_TAG_COLLISION")
        created = previous is None
        if created:
            result = self.runner.run([self.oras, "copy", source_uri, target_tag])
            if result.returncode != 0:
                raise PromotionError("PROMOTION_STORE_COPY_FAILED")
        actual = self._descriptor(target_tag)
        if actual != source_digest:
            raise PromotionError("PROMOTION_TARGET_DIGEST_MISMATCH")
        target_uri = f"{target_repository}@{source_digest}"
        if self._descriptor(target_uri) != source_digest:
            raise PromotionError("PROMOTION_TARGET_READBACK_FAILED")
        return StoreCopy(target_uri, target_tag, created)

    def artifact_exists(self, uri: str) -> bool:
        return self._descriptor(uri) == _uri_digest(uri)

    def tag_digest(self, tag_uri: str) -> str | None:
        return self._descriptor(tag_uri)

    def delete_tag(self, tag_uri: str) -> None:
        match = GHCR_TAG_URI.fullmatch(tag_uri)
        if match is None:
            raise PromotionError("PROMOTION_PARTIAL_TAG_URI_INVALID")
        digest = self._descriptor(tag_uri)
        if digest is None:
            return
        owner = match.group("owner")
        package = match.group("package")
        tag = match.group("tag")
        endpoint = f"/users/{owner}/packages/container/{package}/versions?per_page=100"
        listed = self.runner.run([self.gh, "api", "--paginate", "--slurp", endpoint])
        if listed.returncode != 0:
            raise PromotionError("PROMOTION_PARTIAL_TAG_VERSION_LIST_FAILED")
        try:
            pages = json.loads(listed.stdout)
            versions = [item for page in pages for item in page]
            matches = [
                item for item in versions
                if item.get("name") == digest
                and tag in item.get("metadata", {}).get("container", {}).get("tags", [])
            ]
        except (AttributeError, TypeError, json.JSONDecodeError) as error:
            raise PromotionError("PROMOTION_PARTIAL_TAG_VERSION_LIST_INVALID") from error
        if len(matches) != 1 or not isinstance(matches[0].get("id"), int):
            raise PromotionError("PROMOTION_PARTIAL_TAG_VERSION_NOT_UNIQUE")
        tags = matches[0].get("metadata", {}).get("container", {}).get("tags", [])
        if tags != [tag]:
            raise PromotionError("PROMOTION_PARTIAL_TAG_SHARED_VERSION")
        deleted = self.runner.run([
            self.gh,
            "api",
            "--method",
            "DELETE",
            f"/users/{owner}/packages/container/{package}/versions/{matches[0]['id']}",
        ])
        if deleted.returncode != 0 or self._descriptor(tag_uri) is not None:
            raise PromotionError("PROMOTION_PARTIAL_TAG_CLEANUP_FAILED")


class GitRefLedger:
    """Real GitHub create-only ledger stored behind an immutable tag ruleset."""

    def __init__(self, repository: str, gh: str = "gh", runner: CommandRunner | None = None) -> None:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise PromotionError("PROMOTION_LEDGER_REPOSITORY_INVALID")
        self.repository = repository
        self.gh = gh
        self.runner = runner or CommandRunner()

    def _ref(self, release_version: str, target_environment: str) -> str:
        if not SEMVER.fullmatch(release_version) or target_environment not in TARGET_REPOSITORIES:
            raise PromotionError("PROMOTION_LEDGER_KEY_INVALID")
        return f"refs/tags/promotion-ledger/{release_version}-{target_environment}"

    def uri(self, release_version: str, target_environment: str) -> str:
        ref = self._ref(release_version, target_environment).removeprefix("refs/")
        return f"https://api.github.com/repos/{self.repository}/git/ref/{ref}"

    def _api(self, endpoint: str, method: str = "GET", payload: Any | None = None) -> CommandResult:
        arguments = [self.gh, "api", "--method", method, f"repos/{self.repository}/{endpoint}"]
        stdin = None
        if payload is not None:
            arguments.extend(["--input", "-"])
            stdin = json.dumps(payload, separators=(",", ":"))
        return self.runner.run(arguments, stdin=stdin)

    @staticmethod
    def _json(result: CommandResult, code: str) -> Any:
        if result.returncode != 0:
            raise PromotionError(code)
        try:
            return json.loads(result.stdout)
        except json.JSONDecodeError as error:
            raise PromotionError(code) from error

    def create(self, release_version: str, target_environment: str, record: dict[str, Any]) -> bool:
        payload = canonical_bytes(record) + b"\n"
        blob = self._json(
            self._api(
                "git/blobs",
                "POST",
                {"content": base64.b64encode(payload).decode("ascii"), "encoding": "base64"},
            ),
            "PROMOTION_LEDGER_BLOB_CREATE_FAILED",
        )
        tree = self._json(
            self._api(
                "git/trees",
                "POST",
                {"tree": [{"path": "promotion-record.json", "mode": "100644", "type": "blob", "sha": blob["sha"]}]},
            ),
            "PROMOTION_LEDGER_TREE_CREATE_FAILED",
        )
        commit = self._json(
            self._api(
                "git/commits",
                "POST",
                {"message": f"promotion {release_version} {target_environment}", "tree": tree["sha"]},
            ),
            "PROMOTION_LEDGER_COMMIT_CREATE_FAILED",
        )
        created = self._api(
            "git/refs",
            "POST",
            {"ref": self._ref(release_version, target_environment), "sha": commit["sha"]},
        )
        if created.returncode == 0:
            return True
        if self.read(release_version, target_environment) is not None:
            return False
        raise PromotionError("PROMOTION_LEDGER_CAS_FAILED")

    def read(self, release_version: str, target_environment: str) -> dict[str, Any] | None:
        relative_ref = self._ref(release_version, target_environment).removeprefix("refs/")
        ref_result = self._api(f"git/ref/{relative_ref}")
        if ref_result.returncode != 0:
            if "HTTP 404" in ref_result.stderr or ref_result.stderr == "missing":
                return None
            raise PromotionError("PROMOTION_LEDGER_REF_READ_FAILED")
        ref = self._json(ref_result, "PROMOTION_LEDGER_REF_INVALID")
        try:
            commit_oid = ref["object"]["sha"]
            commit = self._json(self._api(f"git/commits/{commit_oid}"), "PROMOTION_LEDGER_COMMIT_INVALID")
            tree_oid = commit["tree"]["sha"]
            tree = self._json(self._api(f"git/trees/{tree_oid}"), "PROMOTION_LEDGER_TREE_INVALID")
            entry = next(item for item in tree["tree"] if item.get("path") == "promotion-record.json")
            blob = self._json(self._api(f"git/blobs/{entry['sha']}"), "PROMOTION_LEDGER_BLOB_INVALID")
            if blob.get("encoding") != "base64":
                raise PromotionError("PROMOTION_LEDGER_BLOB_INVALID")
            raw = base64.b64decode(blob["content"], validate=True)
            document = parse_json_bytes(raw)
        except (KeyError, StopIteration, TypeError, ValueError) as error:
            if isinstance(error, PromotionError):
                raise
            raise PromotionError("PROMOTION_LEDGER_RECORD_INVALID") from error
        if not isinstance(document, dict) or raw != canonical_bytes(document) + b"\n":
            raise PromotionError("PROMOTION_LEDGER_RECORD_NONCANONICAL")
        return document


class CommandEvidenceVerifier:
    """Freshly re-runs the independent remote verifier before every promotion."""

    def __init__(
        self,
        script: Path,
        identity: str,
        ttl: timedelta,
        runner: CommandRunner | None = None,
    ) -> None:
        self.script = str(script)
        self.identity = identity
        self.ttl = ttl
        self.runner = runner or CommandRunner()

    def verify(self, request: PromotionRequest, now: datetime) -> VerificationReceipt:
        evidence = request.evidence
        environment = os.environ.copy()
        environment.update(
            {
                "ARTIFACT_URI": evidence.artifact_uri,
                "SBOM_URI": evidence.sbom_uri,
                "ATTESTATION_URI": evidence.attestation_uri,
                "WEB_URI": evidence.web_uri,
                "MANIFEST_URI": evidence.manifest_uri,
                "SIGNATURE_URI": evidence.signature_uri,
                "INDEX_URI": evidence.index_uri,
            }
        )
        result = self.runner.run([self.script], environment=environment)
        if result.returncode != 0 or "verify-release: PASS" not in result.stdout:
            raise PromotionError("PROMOTION_CURRENT_VERIFIER_FAILED")
        return VerificationReceipt(evidence, self.identity, now, now + self.ttl)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-version", required=True)
    parser.add_argument("--from-environment", required=True, choices=("candidate", "stage"))
    parser.add_argument("--target-environment", required=True, choices=("stage", "production"))
    for name in ("artifact", "sbom", "attestation", "web", "manifest", "signature", "index"):
        parser.add_argument(f"--{name}-uri", required=True)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--evidence-index-sha256", required=True)
    parser.add_argument("--actor", required=True)
    parser.add_argument("--approver", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-attempt", type=int, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--target-repository", required=True)
    parser.add_argument("--oras", type=Path, required=True)
    parser.add_argument("--gh", default="gh")
    parser.add_argument("--rollback-version")
    parser.add_argument("--rollback-environment", choices=("stage", "production"))
    parser.add_argument("--output", type=Path, required=True)
    return parser


def _write_new_document(path: Path, document: dict[str, Any]) -> None:
    if path.exists():
        raise PromotionError("PROMOTION_OUTPUT_ALREADY_EXISTS")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        with temporary.open("xb") as stream:
            stream.write(canonical_bytes(document) + b"\n")
            stream.flush()
            os.fsync(stream.fileno())
        try:
            os.link(temporary, path)
        except FileExistsError as error:
            raise PromotionError("PROMOTION_OUTPUT_ALREADY_EXISTS") from error
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def main(argv: list[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        if arguments.output.exists():
            raise PromotionError("PROMOTION_OUTPUT_ALREADY_EXISTS")
        artifact_digest = _uri_digest(arguments.artifact_uri)
        if artifact_digest is None:
            raise PromotionError("PROMOTION_IMMUTABLE_URI_REQUIRED: artifact_uri")
        evidence = PromotionEvidence(
            artifact_uri=arguments.artifact_uri,
            sbom_uri=arguments.sbom_uri,
            attestation_uri=arguments.attestation_uri,
            web_uri=arguments.web_uri,
            manifest_uri=arguments.manifest_uri,
            signature_uri=arguments.signature_uri,
            index_uri=arguments.index_uri,
            artifact_oci_digest=artifact_digest,
            manifest_sha256=arguments.manifest_sha256,
            evidence_index_sha256=arguments.evidence_index_sha256,
        )
        ledger = GitRefLedger(arguments.repository, arguments.gh)
        rollback_record = None
        rollback_uri = None
        if arguments.rollback_version or arguments.rollback_environment:
            if not arguments.rollback_version or not arguments.rollback_environment:
                raise PromotionError("PROMOTION_ROLLBACK_KEY_INCOMPLETE")
            rollback_record = ledger.read(arguments.rollback_version, arguments.rollback_environment)
            if rollback_record is None:
                raise PromotionError("PROMOTION_ROLLBACK_LEDGER_MISSING")
            rollback_uri = ledger.uri(arguments.rollback_version, arguments.rollback_environment)
        request = PromotionRequest(
            release_version=arguments.release_version,
            from_environment=arguments.from_environment,
            target_environment=arguments.target_environment,
            evidence=evidence,
            actor=arguments.actor,
            approver=arguments.approver,
            run_id=arguments.run_id,
            run_attempt=arguments.run_attempt,
            rollback_of_ledger_uri=rollback_uri,
        )
        service = PromotionService(
            CommandEvidenceVerifier(
                PROJECT_ROOT / "scripts/verify-release.sh",
                "protected-release-independent-verifier",
                timedelta(minutes=15),
            ),
            OrasDigestStore(arguments.oras),
            ledger,
            {arguments.target_environment: arguments.target_repository},
        )
        now = datetime.now(timezone.utc)
        outcome = (
            service.rollback(request, rollback_record, now)
            if rollback_record is not None
            else service.promote(request, now)
        )
        _write_new_document(arguments.output, outcome.record)
    except (OSError, PromotionError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print(f"promote-release: {outcome.result} {outcome.ledger_uri}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
