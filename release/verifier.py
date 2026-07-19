#!/usr/bin/env python3
"""Independent subject and GitHub attestation verification helpers."""

from __future__ import annotations

import base64
import binascii
import json
import re
import sys
from pathlib import Path
from typing import Any


OCI_URI = re.compile(r"^ghcr\.io/[a-z0-9_.-]+/[a-z0-9_./-]+@sha256:[0-9a-f]{64}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")


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


def main(argv: list[str]) -> int:
    try:
        if len(argv) == 3 and argv[1] == "oci-uri":
            issues = immutable_oci_uri_issues(argv[2])
        elif len(argv) >= 5 and argv[1] == "attestation-query":
            document = json.loads(Path(argv[2]).read_text(encoding="utf-8"))
            issues = attestation_query_issues(document, argv[3], set(argv[4:]))
        else:
            print("usage: verifier.py oci-uri URI | attestation-query FILE SUBJECT PREDICATE...", file=sys.stderr)
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
