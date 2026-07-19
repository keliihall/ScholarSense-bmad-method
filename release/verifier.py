#!/usr/bin/env python3
"""Independent subject and GitHub attestation verification helpers."""

from __future__ import annotations

import base64
import binascii
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tarfile
from datetime import datetime, timezone
from pathlib import Path
from pathlib import PurePosixPath
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from release_json import canonical_bytes, load_json  # noqa: E402
from release_policy import vulnerability_issues  # noqa: E402
from generate_sbom import _findings  # noqa: E402


OCI_URI = re.compile(r"^ghcr\.io/[a-z0-9_.-]+/[a-z0-9_./-]+@sha256:[0-9a-f]{64}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
COMMIT_OID = re.compile(r"^[0-9a-f]{40}$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
SIGNER_WORKFLOW = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/\.github/workflows/[A-Za-z0-9_.-]+\.ya?ml$")


def immutable_oci_uri_issues(uri: Any) -> list[str]:
    if not isinstance(uri, str) or not OCI_URI.fullmatch(uri):
        return ["VERIFIER_IMMUTABLE_OCI_URI_REQUIRED"]
    return []


def attestation_query_issues(
    document: Any,
    expected_subject_sha256: str,
    required_predicate_types: set[str],
) -> list[str]:
    issues: list[str] = []
    if not HEX64.fullmatch(expected_subject_sha256):
        return ["VERIFIER_SUBJECT_DIGEST_INVALID"]
    attestations = document.get("attestations") if isinstance(document, dict) else None
    if not isinstance(attestations, list) or not attestations:
        return ["VERIFIER_ATTESTATION_SET_MISSING"]
    predicates: set[str] = set()
    for index, attestation in enumerate(attestations):
        try:
            raw = attestation["bundle"]["dsseEnvelope"]["payload"]
            decoded = base64.b64decode(raw, validate=True)
            statement = json.loads(decoded.decode("utf-8"))
        except (binascii.Error, KeyError, TypeError, UnicodeDecodeError, json.JSONDecodeError):
            issues.append(f"VERIFIER_ATTESTATION_PAYLOAD_INVALID: {index}")
            continue
        if statement.get("_type") != "https://in-toto.io/Statement/v1":
            issues.append(f"VERIFIER_STATEMENT_TYPE_INVALID: {index}")
        predicate_type = statement.get("predicateType")
        if isinstance(predicate_type, str):
            predicates.add(predicate_type)
        subjects = statement.get("subject")
        if not isinstance(subjects, list) or not any(
            isinstance(subject, dict)
            and isinstance(subject.get("digest"), dict)
            and subject["digest"].get("sha256") == expected_subject_sha256
            for subject in subjects
        ):
            issues.append(f"VERIFIER_ATTESTATION_SUBJECT_MISMATCH: {index}")
    missing = required_predicate_types - predicates
    if missing:
        issues.append(f"VERIFIER_ATTESTATION_PREDICATE_MISSING: {','.join(sorted(missing))}")
    return sorted(set(issues))


def _verified_statement_issues(document: Any, expected_subject_sha256: str, predicate_type: str) -> list[str]:
    if not isinstance(document, list) or not document:
        return ["VERIFIER_ATTESTATION_VERIFICATION_RESULT_MISSING"]
    issues: list[str] = []
    for index, item in enumerate(document):
        result = item.get("verificationResult") if isinstance(item, dict) else None
        statement = result.get("statement") if isinstance(result, dict) else None
        if not isinstance(statement, dict):
            issues.append(f"VERIFIER_ATTESTATION_VERIFICATION_RESULT_INVALID: {index}")
            continue
        if statement.get("predicateType") != predicate_type:
            issues.append(f"VERIFIER_ATTESTATION_PREDICATE_MISMATCH: {index}")
        subjects = statement.get("subject")
        if not isinstance(subjects, list) or not any(
            isinstance(subject, dict)
            and isinstance(subject.get("digest"), dict)
            and subject["digest"].get("sha256") == expected_subject_sha256
            for subject in subjects
        ):
            issues.append(f"VERIFIER_ATTESTATION_SUBJECT_MISMATCH: {index}")
    return sorted(set(issues))


def cryptographically_verify_github_attestations(
    artifact: Path,
    expected_subject_sha256: str,
    repository: str,
    signer_workflow: str,
    source_commit: str,
    required_predicate_types: set[str],
) -> list[str]:
    if (
        not HEX64.fullmatch(expected_subject_sha256)
        or not REPOSITORY.fullmatch(repository)
        or not SIGNER_WORKFLOW.fullmatch(signer_workflow)
        or not COMMIT_OID.fullmatch(source_commit)
        or not required_predicate_types
    ):
        return ["VERIFIER_ATTESTATION_POLICY_INVALID"]
    issues: list[str] = []
    for predicate_type in sorted(required_predicate_types):
        result = subprocess.run(
            [
                "gh",
                "attestation",
                "verify",
                str(artifact),
                "--repo",
                repository,
                "--signer-workflow",
                signer_workflow,
                "--source-digest",
                source_commit,
                "--predicate-type",
                predicate_type,
                "--format",
                "json",
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if result.returncode != 0:
            issues.append("VERIFIER_ATTESTATION_SIGNATURE_INVALID")
            continue
        try:
            verified = json.loads(result.stdout)
        except json.JSONDecodeError:
            issues.append("VERIFIER_ATTESTATION_VERIFICATION_RESULT_INVALID")
            continue
        issues.extend(_verified_statement_issues(verified, expected_subject_sha256, predicate_type))
    return sorted(set(issues))


def pulled_release_material_issues(
    source_root: Path,
    artifact_root: Path,
    sbom_root: Path,
    attestation_root: Path,
    web_root: Path,
    manifest_path: Path,
    signature_path: Path,
    index_path: Path,
    uris: dict[str, str],
) -> list[str]:
    try:
        from assembly import assemble_evidence_index_input, assemble_release_manifest_input
        from manifests import create_evidence_index, create_release_manifest

        if set(uris) != {"artifact", "sbom", "attestation", "web", "manifest", "signature"}:
            return ["VERIFIER_RELEASE_MATERIAL_URI_SET_INVALID"]
        manifest = load_json(manifest_path)
        index = load_json(index_path)
        if not isinstance(manifest, dict) or not isinstance(index, dict):
            return ["VERIFIER_RELEASE_MATERIAL_DOCUMENT_INVALID"]
        payload = assemble_release_manifest_input(
            source_root,
            str(manifest.get("releaseVersion", "")),
            uris["artifact"],
            artifact_root,
            uris["sbom"],
            sbom_root,
            uris["attestation"],
            attestation_root,
            uris["web"],
            web_root,
            str(manifest.get("frozenAt", "")),
        )
        expected_manifest = create_release_manifest(payload)
        if manifest_path.read_bytes() != canonical_bytes(expected_manifest):
            return ["VERIFIER_RELEASE_MANIFEST_MATERIAL_MISMATCH"]
        index_input = assemble_evidence_index_input(
            uris["manifest"],
            manifest_path,
            uris["signature"],
            signature_path,
            str(index.get("createdAt", "")),
        )
        expected_index = create_evidence_index(
            index_input["releaseManifest"],
            index_input["releaseManifestRef"],
            index_input["manifestSignature"],
            index_input["createdAt"],
        )
        if index_path.read_bytes() != canonical_bytes(expected_index):
            return ["VERIFIER_EVIDENCE_INDEX_MATERIAL_MISMATCH"]
    except (OSError, TypeError, ValueError) as error:
        return [f"VERIFIER_RELEASE_MATERIAL_INVALID: {error}"]
    return []


def extract_source_archive(archive: Path, destination: Path, expected_sha256: str) -> None:
    if not HEX64.fullmatch(expected_sha256) or destination.exists() or not destination.parent.is_dir():
        raise ValueError("VERIFIER_SOURCE_ARCHIVE_INPUT_INVALID")
    temporary = destination.with_name(f".{destination.name}.{os.getpid()}.tmp")
    if temporary.exists():
        raise ValueError("VERIFIER_SOURCE_ARCHIVE_TEMP_EXISTS")
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(archive, flags)
    try:
        details = os.fstat(descriptor)
        if not stat.S_ISREG(details.st_mode):
            raise ValueError("VERIFIER_SOURCE_ARCHIVE_NOT_REGULAR")
        with os.fdopen(descriptor, "rb", closefd=True) as stream:
            descriptor = -1
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
            if digest != expected_sha256:
                raise ValueError("VERIFIER_SOURCE_ARCHIVE_DIGEST_MISMATCH")
            stream.seek(0)
            temporary.mkdir(mode=0o700)
            seen: set[str] = set()
            total_size = 0
            with tarfile.open(fileobj=stream, mode="r:gz") as bundle:
                members = bundle.getmembers()
                if len(members) > 10000:
                    raise ValueError("VERIFIER_SOURCE_ARCHIVE_LIMIT_EXCEEDED")
                for member in members:
                    raw_name = member.name
                    path = PurePosixPath(raw_name)
                    if (
                        not raw_name
                        or "\\" in raw_name
                        or path.is_absolute()
                        or any(part in {"", ".", ".."} for part in path.parts)
                        or raw_name in seen
                        or not (member.isdir() or member.isfile())
                    ):
                        raise ValueError("VERIFIER_SOURCE_ARCHIVE_ENTRY_INVALID")
                    seen.add(raw_name)
                    total_size += member.size
                    if total_size > 1024 * 1024 * 1024:
                        raise ValueError("VERIFIER_SOURCE_ARCHIVE_LIMIT_EXCEEDED")
                    target = temporary.joinpath(*path.parts)
                    if member.isdir():
                        target.mkdir(parents=True, exist_ok=True)
                        continue
                    target.parent.mkdir(parents=True, exist_ok=True)
                    source = bundle.extractfile(member)
                    if source is None:
                        raise ValueError("VERIFIER_SOURCE_ARCHIVE_ENTRY_INVALID")
                    with source, target.open("xb") as output:
                        shutil.copyfileobj(source, output, 1024 * 1024)
                    if target.stat().st_size != member.size:
                        raise ValueError("VERIFIER_SOURCE_ARCHIVE_ENTRY_SIZE_MISMATCH")
            os.replace(temporary, destination)
    except BaseException:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def main(argv: list[str]) -> int:
    if len(argv) == 4 and argv[1] == "current-vulnerability-policy":
        try:
            scan = load_json(Path(argv[2]))
            policy = load_json(PROJECT_ROOT / "contracts/release/vulnerability-policy-1.0.0.json")
            vulnerabilities = scan.get("vulnerabilities") if isinstance(scan, dict) else None
            if not isinstance(vulnerabilities, list) or not HEX64.fullmatch(argv[3]):
                raise VerificationError("VERIFIER_CURRENT_SCAN_INVALID")
            issues = vulnerability_issues(
                _findings(vulnerabilities),
                policy,
                policy.get("exceptions", []),
                argv[3],
                datetime.now(timezone.utc),
            )
            if issues:
                raise VerificationError(issues[0])
        except (OSError, ValueError, VerificationError) as error:
            print(error, file=sys.stderr)
            return 1
        print("current-vulnerability-policy: PASS")
        return 0
    try:
        if len(argv) == 3 and argv[1] == "oci-uri":
            issues = immutable_oci_uri_issues(argv[2])
        elif len(argv) >= 5 and argv[1] == "attestation-query":
            document = json.loads(Path(argv[2]).read_text(encoding="utf-8"))
            issues = attestation_query_issues(document, argv[3], set(argv[4:]))
        elif len(argv) >= 8 and argv[1] == "github-attestations":
            issues = cryptographically_verify_github_attestations(
                Path(argv[2]), argv[3], argv[4], argv[5], argv[6], set(argv[7:])
            )
        elif len(argv) == 16 and argv[1] == "pulled-material":
            issues = pulled_release_material_issues(
                Path(argv[2]), Path(argv[3]), Path(argv[4]), Path(argv[5]), Path(argv[6]),
                Path(argv[7]), Path(argv[8]), Path(argv[9]),
                {
                    "artifact": argv[10], "sbom": argv[11], "attestation": argv[12],
                    "web": argv[13], "manifest": argv[14], "signature": argv[15],
                },
            )
        elif len(argv) == 5 and argv[1] == "extract-source":
            extract_source_archive(Path(argv[2]), Path(argv[3]), argv[4])
            issues = []
        else:
            print(
                "usage: verifier.py oci-uri URI | attestation-query FILE SUBJECT PREDICATE... | "
                "github-attestations ARTIFACT SUBJECT REPOSITORY SIGNER_WORKFLOW SOURCE_COMMIT PREDICATE...",
                file=sys.stderr,
            )
            return 2
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        print(error, file=sys.stderr)
        return 1
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print("release-verifier-helper: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
