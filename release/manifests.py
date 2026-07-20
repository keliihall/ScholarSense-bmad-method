#!/usr/bin/env python3
"""Freeze release manifests and their post-signature evidence index."""

from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


PROJECT_ROOT = Path(__file__).resolve().parents[1]
import sys

sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from release_json import canonical_bytes, canonical_sha256, release_document_issues  # noqa: E402


HEX64 = re.compile(r"^[0-9a-f]{64}$")
SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
REQUIRED_BASELINE_IDS = frozenset({"AAB", "CISB", "FPB", "PAB", "TEST-ENV", "UXB", "VGB"})
REQUIRED_CONTROLLED_INPUT_IDS = frozenset(
    {
        "AcademicCareNodeSet",
        "Availability",
        "BusinessCalendar",
        "CareActionCatalog",
        "EvidenceSchema",
        "MetricPublication",
        "PerformanceProfile",
        "QualityRecovery",
        "Queue",
        "RetentionSchedule",
        "RoleField",
        "Rule",
        "SeasonalProgramMatrix",
        "StrategyGate",
        "TransferSla",
        "WorkVisit",
    }
)
REQUIRED_LOCK_IDS = frozenset({"backend-lock", "frontend-lock", "toolchain-lock"})
COMMON_EVIDENCE_KINDS = frozenset(
    {
        "artifact-signature",
        "provenance",
        "sbom-attestation",
        "sbom-cyclonedx",
        "sbom-spdx",
        "vulnerability-scan",
    }
)
FRONTEND_EVIDENCE_KINDS = frozenset(
    {"brand-asset-manifest", "formal-web-report", "ui-token-manifest", "visual-baseline"}
)
HOST_SSO_EVIDENCE_KIND = "host-sso-runtime-evidence"
BLOCKING_RUNTIME_IDS = frozenset({"formal-web-evidence", "supply-chain-evidence"})
RUNTIME_IDS = frozenset({"app-webview", "formal-web-evidence", "future-app-device", "supply-chain-evidence"})
FORBIDDEN_RELEASE_KEYS = frozenset(
    {"evidenceIndex", "evidenceIndexUri", "manifestSignature", "manifestSignatureUri", "promotion", "promotionUri"}
)
STAGE_ORDER = {"artifact": 10, "artifact-evidence": 20, "artifact-signature": 30}


def _ids(items: Any, code: str) -> tuple[set[str], list[str]]:
    issues: list[str] = []
    identities: set[str] = set()
    if not isinstance(items, list):
        return identities, [code]
    for item in items:
        identity = item.get("id") if isinstance(item, dict) else None
        if not isinstance(identity, str) or not identity:
            issues.append(code)
        elif identity in identities:
            issues.append(f"{code}_DUPLICATE: {identity}")
        else:
            identities.add(identity)
    return identities, issues


def _reference_issues(reference: Any, label: str) -> list[str]:
    if not isinstance(reference, dict):
        return [f"{label}_INVALID"]
    issues: list[str] = []
    for key in ("id", "version", "uri", "mediaType"):
        if not isinstance(reference.get(key), str) or not reference[key]:
            issues.append(f"{label}_{key.upper()}_INVALID")
    if not isinstance(reference.get("size"), int) or isinstance(reference.get("size"), bool) or reference["size"] < 1:
        issues.append(f"{label}_SIZE_INVALID")
    binary_digest = reference.get("binarySha256")
    if not isinstance(binary_digest, str) or not HEX64.fullmatch(binary_digest):
        issues.append(f"{label}_BINARY_SHA256_INVALID")
    oci_digest = reference.get("ociDigest")
    if not isinstance(oci_digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", oci_digest):
        issues.append(f"{label}_OCI_DIGEST_INVALID")
    uri = reference.get("uri")
    if isinstance(uri, str):
        parsed = urlparse(uri)
        if parsed.scheme == "oci":
            suffix = uri.rpartition("@sha256:")[2]
            if not suffix or f"sha256:{suffix}" != oci_digest:
                issues.append(f"{label}_URI_DIGEST_MISMATCH")
        elif parsed.scheme == "https":
            immutable = bool(
                re.search(r"/(?:actions/runs|commit|blob)/[0-9a-f0-9][^?#/]*(?:/|$)", parsed.path)
                or "sha256:" in uri
            )
            if not immutable:
                issues.append(f"{label}_URI_MUTABLE")
        else:
            issues.append(f"{label}_URI_SCHEME_FORBIDDEN")
    return issues


def _exact_ids(items: Any, expected: frozenset[str], code: str) -> list[str]:
    actual, issues = _ids(items, code)
    if actual != set(expected):
        missing = ",".join(sorted(expected - actual))
        extra = ",".join(sorted(actual - expected))
        issues.append(f"{code}_SET_INVALID: missing={missing};extra={extra}")
    return issues


def release_manifest_issues(manifest: Any, build_manifest: Any) -> list[str]:
    if not isinstance(manifest, dict):
        return ["RELEASE_MANIFEST_INVALID"]
    if not isinstance(build_manifest, dict):
        return ["RELEASE_BUILD_MANIFEST_INVALID"]
    issues = release_document_issues(manifest)
    forbidden = sorted(FORBIDDEN_RELEASE_KEYS & set(manifest))
    if forbidden or any(key.startswith("promotion") for key in manifest):
        issues.append(f"RELEASE_DESCENDANT_REFERENCE_FORBIDDEN: {','.join(forbidden)}")
    if manifest.get("version") != "RELEASE-MANIFEST-1.0.0":
        issues.append("RELEASE_MANIFEST_VERSION_INVALID")
    if not SEMVER.fullmatch(str(manifest.get("releaseVersion", ""))):
        issues.append("RELEASE_VERSION_INVALID")
    if manifest.get("sourceCommit") != build_manifest.get("sourceCommit"):
        issues.append("RELEASE_SOURCE_COMMIT_MISMATCH")
    if manifest.get("canonicalizationProfile") != "SCHOLARSENSE-CANONICAL-JSON-1.0.0":
        issues.append("RELEASE_CANONICAL_PROFILE_INVALID")
    if manifest.get("buildAttempts") != build_manifest.get("attempts"):
        issues.append("RELEASE_BUILD_ATTEMPTS_MISMATCH")

    build_reference = manifest.get("buildManifest")
    issues.extend(_reference_issues(build_reference, "RELEASE_BUILD_MANIFEST_REF"))
    if isinstance(build_reference, dict):
        if build_reference.get("id") != "build-manifest":
            issues.append("RELEASE_BUILD_MANIFEST_ID_INVALID")
        if build_reference.get("version") != build_manifest.get("version"):
            issues.append("RELEASE_BUILD_MANIFEST_VERSION_MISMATCH")
        if build_reference.get("binarySha256") != canonical_sha256(build_manifest):
            issues.append("RELEASE_BUILD_MANIFEST_DIGEST_MISMATCH")
    source_reference = manifest.get("sourceInventory")
    issues.extend(_reference_issues(source_reference, "RELEASE_SOURCE_INVENTORY_REF"))
    if isinstance(source_reference, dict) and source_reference.get("id") != "release-source-inventory":
        issues.append("RELEASE_SOURCE_INVENTORY_ID_INVALID")
    source_archive = manifest.get("sourceArchive")
    issues.extend(_reference_issues(source_archive, "RELEASE_SOURCE_ARCHIVE_REF"))
    if isinstance(source_archive, dict):
        if source_archive.get("id") != "release-source-archive":
            issues.append("RELEASE_SOURCE_ARCHIVE_ID_INVALID")
        if source_archive.get("version") != manifest.get("sourceCommit"):
            issues.append("RELEASE_SOURCE_ARCHIVE_COMMIT_MISMATCH")

    baseline_approvals = manifest.get("baselineApprovals")
    issues.extend(_exact_ids(baseline_approvals, REQUIRED_BASELINE_IDS, "RELEASE_BASELINE"))
    if isinstance(baseline_approvals, list):
        for baseline in baseline_approvals:
            issues.extend(_reference_issues(baseline, "RELEASE_BASELINE_REF"))
            if isinstance(baseline, dict) and baseline.get("status") != "approved":
                issues.append(f"RELEASE_BASELINE_NOT_APPROVED: {baseline.get('id')}")

    controlled_inputs = manifest.get("controlledInputs")
    issues.extend(_exact_ids(controlled_inputs, REQUIRED_CONTROLLED_INPUT_IDS, "RELEASE_CONTROLLED_INPUT"))
    if isinstance(controlled_inputs, list):
        for controlled_input in controlled_inputs:
            issues.extend(_reference_issues(controlled_input, "RELEASE_CONTROLLED_INPUT_REF"))

    locks = manifest.get("locks")
    issues.extend(_exact_ids(locks, REQUIRED_LOCK_IDS, "RELEASE_LOCK"))
    if isinstance(locks, list):
        for lock in locks:
            issues.extend(_reference_issues(lock, "RELEASE_LOCK_REF"))

    artifact_ids, artifact_id_issues = _ids(manifest.get("artifacts"), "RELEASE_ARTIFACT_ID_INVALID")
    issues.extend(artifact_id_issues)
    if artifact_ids != {"backend", "frontend"}:
        issues.append("RELEASE_ARTIFACT_ID_SET_INVALID")
    build_by_name = {
        item.get("name"): item
        for item in build_manifest.get("artifacts", [])
        if isinstance(item, dict)
    }
    artifact_by_digest: dict[str, str] = {}
    for artifact in manifest.get("artifacts", []) if isinstance(manifest.get("artifacts"), list) else []:
        issues.extend(_reference_issues(artifact, "RELEASE_ARTIFACT_REF"))
        if not isinstance(artifact, dict):
            continue
        if artifact.get("kind") != "artifact":
            issues.append(f"RELEASE_ARTIFACT_KIND_INVALID: {artifact.get('id')}")
        build_artifact = build_by_name.get(artifact.get("name"))
        for field in ("binarySha256", "mediaType", "size"):
            if not isinstance(build_artifact, dict) or artifact.get(field) != build_artifact.get(field):
                issues.append(f"RELEASE_ARTIFACT_{field.upper()}_MISMATCH: {artifact.get('id')}")
        digest = artifact.get("binarySha256")
        if isinstance(digest, str):
            artifact_by_digest[digest] = str(artifact.get("id"))

    evidence_ids, evidence_id_issues = _ids(manifest.get("evidence"), "RELEASE_EVIDENCE_ID_INVALID")
    issues.extend(evidence_id_issues)
    if artifact_ids & evidence_ids:
        issues.append("RELEASE_ARTIFACT_EVIDENCE_ID_COLLISION")
    kinds_by_subject: dict[str, set[str]] = {identity: set() for identity in artifact_ids}
    for evidence in manifest.get("evidence", []) if isinstance(manifest.get("evidence"), list) else []:
        issues.extend(_reference_issues(evidence, "RELEASE_EVIDENCE_REF"))
        if not isinstance(evidence, dict):
            continue
        kind = evidence.get("kind")
        if not isinstance(kind, str) or not kind:
            issues.append(f"RELEASE_EVIDENCE_KIND_INVALID: {evidence.get('id')}")
        subject = evidence.get("subjectBinarySha256")
        if subject not in artifact_by_digest:
            issues.append(f"RELEASE_EVIDENCE_SUBJECT_UNKNOWN: {evidence.get('id')}")
        elif isinstance(kind, str):
            subject_id = artifact_by_digest[subject]
            kinds_by_subject[subject_id].add(kind)
            if kind == HOST_SSO_EVIDENCE_KIND and subject_id != "frontend":
                issues.append(
                    f"RELEASE_HOST_SSO_EVIDENCE_SUBJECT_INVALID: {evidence.get('id')}"
                )
    for artifact_id in sorted(artifact_ids):
        required = set(COMMON_EVIDENCE_KINDS)
        if artifact_id == "frontend":
            required.update(FRONTEND_EVIDENCE_KINDS)
        missing = required - kinds_by_subject.get(artifact_id, set())
        if missing:
            issues.append(f"RELEASE_REQUIRED_EVIDENCE_MISSING: {artifact_id}: {','.join(sorted(missing))}")

    runtime = manifest.get("runtimeEvidence")
    issues.extend(_exact_ids(runtime, RUNTIME_IDS, "RELEASE_RUNTIME"))
    runtime_by_id = {
        item.get("id"): item for item in runtime if isinstance(item, dict)
    } if isinstance(runtime, list) else {}
    for identity in BLOCKING_RUNTIME_IDS:
        item = runtime_by_id.get(identity, {})
        if item.get("status") != "passed":
            issues.append(f"RELEASE_RUNTIME_GATE_NOT_PASSED: {identity}")
        linked = item.get("evidenceIds")
        if not isinstance(linked, list) or not linked or any(value not in evidence_ids for value in linked):
            issues.append(f"RELEASE_RUNTIME_EVIDENCE_LINK_INVALID: {identity}")
            continue
        linked_evidence = {
            evidence.get("id"): evidence
            for evidence in manifest.get("evidence", [])
            if isinstance(evidence, dict) and evidence.get("id") in linked
        }
        for evidence_id in linked:
            evidence = linked_evidence.get(evidence_id, {})
            kind = evidence.get("kind")
            subject_id = artifact_by_digest.get(evidence.get("subjectBinarySha256"))
            if identity == "formal-web-evidence" and (
                kind not in FRONTEND_EVIDENCE_KINDS or subject_id != "frontend"
            ):
                issues.append(f"RELEASE_RUNTIME_EVIDENCE_SEMANTICS_INVALID: {identity}: {evidence_id}")
            if identity == "supply-chain-evidence" and kind not in COMMON_EVIDENCE_KINDS:
                issues.append(f"RELEASE_RUNTIME_EVIDENCE_SEMANTICS_INVALID: {identity}: {evidence_id}")
    app = runtime_by_id.get("app-webview", {})
    if app.get("status") != "not-applicable" or app.get("decisionId") != "USER-2026-07-19-SCHOOL-APP-NA" or app.get("runtimeEvidenceClaim") != "none" or "evidenceIds" in app:
        issues.append("RELEASE_APP_NA_DECISION_INVALID")
    future = runtime_by_id.get("future-app-device", {})
    if future.get("status") != "pending-story-execution" or future.get("runtimeEvidenceClaim") != "none" or not future.get("ownerStory") or "evidenceIds" in future:
        issues.append("RELEASE_FUTURE_STORY_STATUS_INVALID")
    return sorted(set(issues))


def create_release_manifest(payload: dict[str, Any]) -> dict[str, Any]:
    build_manifest = payload.get("buildManifest")
    manifest = {
        "version": "RELEASE-MANIFEST-1.0.0",
        "releaseVersion": payload.get("releaseVersion"),
        "sourceCommit": build_manifest.get("sourceCommit") if isinstance(build_manifest, dict) else None,
        "sourceInventory": payload.get("sourceInventoryRef"),
        "sourceArchive": payload.get("sourceArchiveRef"),
        "buildManifest": payload.get("buildManifestRef"),
        "buildAttempts": build_manifest.get("attempts") if isinstance(build_manifest, dict) else None,
        "canonicalizationProfile": "SCHOLARSENSE-CANONICAL-JSON-1.0.0",
        "baselineApprovals": payload.get("baselineApprovals"),
        "runtimeEvidence": payload.get("runtimeEvidence"),
        "controlledInputs": payload.get("controlledInputs"),
        "locks": payload.get("locks"),
        "artifacts": payload.get("artifacts"),
        "evidence": payload.get("evidence"),
        "frozenAt": payload.get("frozenAt"),
    }
    issues = release_manifest_issues(manifest, build_manifest)
    if issues:
        raise ValueError(issues[0])
    return manifest


def _index_node(reference: dict[str, Any], stage: str, depends_on: list[str]) -> dict[str, Any]:
    return {
        "id": reference["id"],
        "kind": reference["kind"],
        "stage": stage,
        "version": reference["version"],
        "uri": reference["uri"],
        "mediaType": reference["mediaType"],
        "size": reference["size"],
        "binarySha256": reference["binarySha256"],
        "ociDigest": reference["ociDigest"],
        "subjectBinarySha256": reference.get("subjectBinarySha256", reference["binarySha256"]),
        "dependsOn": depends_on,
    }


def create_evidence_index(
    release_manifest: dict[str, Any],
    release_manifest_reference: dict[str, Any],
    manifest_signature: dict[str, Any],
    created_at: str,
) -> dict[str, Any]:
    manifest_digest = canonical_sha256(release_manifest)
    artifacts = release_manifest.get("artifacts", [])
    artifact_by_subject = {item["binarySha256"]: item["id"] for item in artifacts}
    evidence = release_manifest.get("evidence", [])
    ordinary = [item for item in evidence if item.get("kind") != "artifact-signature"]
    signatures = [item for item in evidence if item.get("kind") == "artifact-signature"]
    nodes = [_index_node(item, "artifact", []) for item in artifacts]
    nodes.extend(
        _index_node(item, "artifact-evidence", [artifact_by_subject.get(item["subjectBinarySha256"], "")])
        for item in ordinary
    )
    for item in signatures:
        subject = item["subjectBinarySha256"]
        prerequisites = [artifact_by_subject.get(subject, "")]
        prerequisites.extend(
            candidate["id"]
            for candidate in ordinary
            if candidate.get("subjectBinarySha256") == subject
            and candidate.get("kind") in {"provenance", "sbom-attestation"}
        )
        nodes.append(_index_node(item, "artifact-signature", prerequisites))
    signature_node = _index_node(manifest_signature, "manifest-signature", manifest_signature.get("dependsOn", []))
    index = {
        "version": "EVIDENCE-INDEX-1.0.0",
        "releaseVersion": release_manifest.get("releaseVersion"),
        "subjectManifestSha256": manifest_digest,
        "releaseManifest": release_manifest_reference,
        "manifestSignature": signature_node,
        "evidence": nodes,
        "versionBindings": [
            {"releaseVersion": release_manifest.get("releaseVersion"), "manifestSha256": manifest_digest}
        ],
        "createdAt": created_at,
    }
    issues = evidence_index_issues(index, release_manifest)
    if issues:
        raise ValueError(issues[0])
    return index


def evidence_index_issues(index: Any, release_manifest: Any) -> list[str]:
    if not isinstance(index, dict) or not isinstance(release_manifest, dict):
        return ["EVIDENCE_INDEX_INVALID"]
    issues = release_document_issues(index)
    manifest_digest = canonical_sha256(release_manifest)
    if index.get("version") != "EVIDENCE-INDEX-1.0.0":
        issues.append("EVIDENCE_INDEX_VERSION_INVALID")
    if index.get("releaseVersion") != release_manifest.get("releaseVersion"):
        issues.append("EVIDENCE_INDEX_RELEASE_VERSION_MISMATCH")
    if index.get("subjectManifestSha256") != manifest_digest:
        issues.append("EVIDENCE_INDEX_SUBJECT_MISMATCH")
    manifest_reference = index.get("releaseManifest")
    issues.extend(_reference_issues(manifest_reference, "EVIDENCE_INDEX_MANIFEST_REF"))
    if not isinstance(manifest_reference, dict) or manifest_reference.get("id") != "release-manifest" or manifest_reference.get("binarySha256") != manifest_digest:
        issues.append("EVIDENCE_INDEX_MANIFEST_REF_MISMATCH")
    signature = index.get("manifestSignature")
    issues.extend(_reference_issues(signature, "EVIDENCE_INDEX_SIGNATURE_REF"))
    if not isinstance(signature, dict) or signature.get("id") != "manifest-signature" or signature.get("kind") != "manifest-signature" or signature.get("stage") != "manifest-signature":
        issues.append("EVIDENCE_INDEX_SIGNATURE_KIND_INVALID")
    elif signature.get("subjectBinarySha256") != manifest_digest:
        issues.append("EVIDENCE_INDEX_SIGNATURE_SUBJECT_MISMATCH")
    if not isinstance(signature, dict) or signature.get("dependsOn") != ["release-manifest"]:
        issues.append("EVIDENCE_INDEX_SIGNATURE_ORDER_INVALID")

    expected_references = {
        item["id"]: item
        for item in release_manifest.get("artifacts", []) + release_manifest.get("evidence", [])
    }
    nodes = index.get("evidence")
    node_ids, node_issues = _ids(nodes, "EVIDENCE_INDEX_NODE_ID_INVALID")
    issues.extend(node_issues)
    if node_ids != set(expected_references):
        issues.append("EVIDENCE_INDEX_NODE_SET_MISMATCH")
    positions = {
        item["id"]: position for position, item in enumerate(nodes) if isinstance(item, dict) and isinstance(item.get("id"), str)
    } if isinstance(nodes, list) else {}
    dependencies: dict[str, list[str]] = {}
    for node in nodes if isinstance(nodes, list) else []:
        issues.extend(_reference_issues(node, "EVIDENCE_INDEX_NODE_REF"))
        if not isinstance(node, dict):
            continue
        identity = node.get("id")
        reference = expected_references.get(identity)
        if not isinstance(reference, dict) or any(node.get(field) != reference.get(field) for field in ("kind", "version", "uri", "mediaType", "size", "binarySha256", "ociDigest")):
            issues.append(f"EVIDENCE_INDEX_NODE_REFERENCE_MISMATCH: {identity}")
        expected_subject = reference.get("subjectBinarySha256", reference.get("binarySha256")) if isinstance(reference, dict) else None
        if node.get("subjectBinarySha256") != expected_subject:
            issues.append(f"EVIDENCE_INDEX_NODE_SUBJECT_MISMATCH: {identity}")
        expected_stage = "artifact" if node.get("kind") == "artifact" else (
            "artifact-signature" if node.get("kind") == "artifact-signature" else "artifact-evidence"
        )
        if node.get("stage") != expected_stage:
            issues.append(f"EVIDENCE_INDEX_STAGE_INVALID: {identity}")
        values = node.get("dependsOn")
        if not isinstance(values, list):
            issues.append(f"EVIDENCE_INDEX_DEPENDENCY_INVALID: {identity}")
            continue
        dependencies[str(identity)] = values
        for dependency in values:
            if dependency in {"manifest-signature", "evidence-index"} or str(dependency).startswith("promotion"):
                issues.append(f"EVIDENCE_INDEX_REVERSE_DEPENDENCY: {identity}->{dependency}")
            elif dependency not in positions:
                issues.append(f"EVIDENCE_INDEX_DEPENDENCY_MISSING: {identity}->{dependency}")
            elif positions[dependency] >= positions.get(str(identity), -1):
                issues.append(f"EVIDENCE_INDEX_ORDER_INVALID: {identity}->{dependency}")
            elif STAGE_ORDER.get(nodes[positions[dependency]].get("stage"), 99) >= STAGE_ORDER.get(node.get("stage"), -1):
                issues.append(f"EVIDENCE_INDEX_STAGE_ORDER_INVALID: {identity}->{dependency}")
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(identity: str) -> None:
        if identity in visiting:
            issues.append(f"EVIDENCE_INDEX_CYCLE: {identity}")
            return
        if identity in visited:
            return
        visiting.add(identity)
        for dependency in dependencies.get(identity, []):
            if dependency in dependencies:
                visit(dependency)
        visiting.remove(identity)
        visited.add(identity)

    for identity in dependencies:
        visit(identity)
    bindings: dict[str, str] = {}
    for binding in index.get("versionBindings", []):
        if not isinstance(binding, dict):
            issues.append("EVIDENCE_INDEX_VERSION_BINDING_INVALID")
            continue
        version = binding.get("releaseVersion")
        digest = binding.get("manifestSha256")
        if version in bindings and bindings[version] != digest:
            issues.append(f"EVIDENCE_INDEX_VERSION_DIGEST_REBIND: {version}")
        if isinstance(version, str) and isinstance(digest, str):
            bindings[version] = digest
    if bindings.get(str(release_manifest.get("releaseVersion"))) != manifest_digest:
        issues.append("EVIDENCE_INDEX_VERSION_BINDING_MISSING")
    return sorted(set(issues))


def write_frozen_document(path: Path, document: dict[str, Any]) -> None:
    payload = canonical_bytes(document)
    target = path.resolve()
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
    except FileExistsError:
        if target.read_bytes() == payload:
            return
        raise RuntimeError("MANIFEST_ALREADY_FROZEN") from None
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
    except BaseException:
        target.unlink(missing_ok=True)
        raise
