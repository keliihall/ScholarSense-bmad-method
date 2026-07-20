#!/usr/bin/env python3
"""Validate Story 1.2 host/session contracts, examples, and immutable lock."""

from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


sys.path.insert(0, str(Path(__file__).resolve().parent))

from release_json import load_json, schema_definition_issues, schema_issues  # noqa: E402


LOCK = Path("contracts/identity/identity-contract-lock-1.0.0.json")
SCHEMAS = {
    "contracts/host/examples/valid/host-challenge.json": "contracts/host/host-challenge.schema.json",
    "contracts/host/examples/valid/host-ready.json": "contracts/host/host-envelope.schema.json",
    "contracts/host/examples/valid/navigate-requested.json": "contracts/host/host-envelope.schema.json",
    "contracts/host/examples/valid/acknowledged.json": "contracts/host/host-response.schema.json",
    "contracts/host/examples/valid/failed.json": "contracts/host/host-response.schema.json",
    "contracts/identity/examples/valid/bootstrap-exchange-request.json": "contracts/identity/bootstrap-exchange.schema.json",
    "contracts/identity/examples/valid/current-session.json": "contracts/identity/current-session.schema.json",
    "contracts/identity/examples/valid/reauthentication-request.json": "contracts/identity/reauthentication.schema.json",
    "contracts/identity/examples/valid/logout-request.json": "contracts/identity/session-command.schema.json",
    "contracts/identity/examples/valid/account-switch-request.json": "contracts/identity/session-command.schema.json",
    "contracts/identity/examples/valid/continuation.json": "contracts/identity/continuation.schema.json",
    "contracts/identity/examples/valid/version-conflict-error.json": "contracts/identity/error-envelope.schema.json",
    "contracts/identity/identity-runtime-profile-1.0.0.json": "contracts/identity/identity-runtime-profile.schema.json",
}
HOST_EVENTS = {
    "host.ready": {"bootstrapCode"},
    "auth.changed": set(),
    "navigate.requested": {"targetRouteId"},
    "logout.requested": set(),
    "theme.changed": {"theme"},
}
SENSITIVE = re.compile(r"(?:token|ticket|password|secret|authorization|student|decision)", re.I)


def validate(project_root: Path) -> list[str]:
    root = project_root.resolve()
    issues: list[str] = []
    schemas: dict[str, Any] = {}
    for relative in sorted(set(SCHEMAS.values())):
        try:
            schema = load_json(root / relative)
        except (OSError, ValueError):
            issues.append(f"IDENTITY_CONTRACT_SCHEMA_INVALID: {relative}")
            continue
        schemas[relative] = schema
        issues.extend(f"IDENTITY_CONTRACT_SCHEMA_INVALID: {relative}: {issue}"
                      for issue in schema_definition_issues(schema))

    for instance_path, schema_path in SCHEMAS.items():
        try:
            instance = load_json(root / instance_path)
        except (OSError, ValueError):
            issues.append(f"IDENTITY_CONTRACT_EXAMPLE_INVALID: {instance_path}")
            continue
        issues.extend(_semantic_issues(root, instance_path, instance))
        schema = schemas.get(schema_path)
        if schema is not None:
            validation = schema_issues(instance, schema)
            if validation:
                label = "HOST_MESSAGE_SCHEMA_REJECTED" if instance_path.startswith("contracts/host/") \
                    else "IDENTITY_CONTRACT_EXAMPLE_REJECTED"
                issues.append(f"{label}: {instance_path}: {validation[0]}")

    issues.extend(_catalog_issues(root))
    issues.extend(_lock_issues(root))
    return sorted(set(issues))


def _semantic_issues(root: Path, relative: str, value: Any) -> list[str]:
    if not isinstance(value, dict):
        return []
    issues: list[str] = []
    if relative.endswith("host-challenge.json"):
        payload = value.get("payload")
        if value.get("eventType") != "host.challenge" or not isinstance(payload, dict) \
                or set(payload) != {"bootstrapCode", "audience", "origin", "expiresAt"}:
            issues.append(f"HOST_CHALLENGE_PAYLOAD_INVALID: {relative}")
    if relative.endswith(("host-ready.json", "navigate-requested.json")):
        event_type = value.get("eventType")
        if event_type not in HOST_EVENTS:
            issues.append(f"HOST_EVENT_TYPE_INVALID: {relative}")
        payload = value.get("payload")
        if isinstance(payload, dict):
            if any(SENSITIVE.search(str(key)) for key in payload):
                issues.append(f"HOST_PAYLOAD_SENSITIVE_FIELD_FORBIDDEN: {relative}")
            allowed = HOST_EVENTS.get(event_type)
            if allowed is not None and set(payload) != allowed:
                issues.append(f"HOST_EVENT_PAYLOAD_INVALID: {relative}")
    if relative.endswith("reauthentication-request.json"):
        target = value.get("targetRouteId")
        if target not in {"shell.home", "shell.session"}:
            issues.append(f"CONTINUATION_TARGET_FORBIDDEN: {relative}")
    if relative.endswith("identity-runtime-profile-1.0.0.json"):
        runtime_bindings = value.get("runtimeBindings", {})
        missing = value.get("missingRequirements", [])
        has_clock_binding = isinstance(runtime_bindings, dict) and "clockSource" in runtime_bindings
        evidence = value.get("evidence", {})
        clock_evidence = evidence.get("clockSynchronization") if isinstance(evidence, dict) else None
        reports_clock_missing = isinstance(missing, list) and "clockSource" in missing
        if has_clock_binding == reports_clock_missing:
            issues.append(f"IDENTITY_CLOCK_BINDING_STATUS_INVALID: {relative}")
        expected_status = "evidence-verified" if has_clock_binding else "runtime-evidence-required"
        if value.get("clockBindingStatus") != expected_status:
            issues.append(f"IDENTITY_CLOCK_BINDING_STATUS_INVALID: {relative}")
        if has_clock_binding:
            source_id = str(runtime_bindings["clockSource"]).rstrip("/").rsplit("/", 1)[-1]
            if not isinstance(clock_evidence, str) \
                    or not re.fullmatch(r"evidence://signed/clock/[A-Za-z0-9._-]+\.json", clock_evidence) \
                    or clock_evidence.rsplit("/", 1)[-1] != source_id + ".json":
                issues.append(f"IDENTITY_CLOCK_EVIDENCE_INVALID: {relative}")
            else:
                issues.extend(_clock_evidence_issues(
                    root, relative, value, source_id, clock_evidence))
        elif clock_evidence is not None:
            issues.append(f"IDENTITY_CLOCK_EVIDENCE_INVALID: {relative}")
        if value.get("auditClockBindingContract") != \
                "contracts/audit/trusted-clock-runtime-binding-1.0.0.json":
            issues.append(f"IDENTITY_CLOCK_BINDING_CONTRACT_INVALID: {relative}")
    if "/examples/valid/" in relative and _contains_sensitive_key(value):
        permitted = {"csrfToken", "authorizationUri"}
        if any(key not in permitted and SENSITIVE.search(key) for key in _all_keys(value)):
            issues.append(f"IDENTITY_CONTRACT_SENSITIVE_FIELD_FORBIDDEN: {relative}")
    return issues


def _clock_evidence_issues(
        root: Path,
        relative: str,
        profile: dict[str, Any],
        source_id: str,
        evidence_ref: str) -> list[str]:
    evidence_root = (root / "evidence/signed").resolve()
    evidence_path = (evidence_root / evidence_ref.removeprefix("evidence://signed/")).resolve()
    try:
        evidence_path.relative_to(evidence_root)
        evidence = load_json(evidence_path)
    except (OSError, ValueError):
        return [f"IDENTITY_CLOCK_EVIDENCE_INVALID: {relative}"]
    expected_fields = {
        "version", "status", "sourceId", "clockBindingVersion", "sourceCommit",
        "offsetMs", "observedAt", "freshUntil",
    }
    if not isinstance(evidence, dict) or set(evidence) != expected_fields:
        return [f"IDENTITY_CLOCK_EVIDENCE_INVALID: {relative}"]
    try:
        observed = _utc_instant(evidence.get("observedAt"))
        fresh = _utc_instant(evidence.get("freshUntil"))
    except (TypeError, ValueError):
        observed = fresh = None
    valid = (
        evidence.get("version") == "CLOCK-SYNCHRONIZATION-EVIDENCE-1.0.0"
        and evidence.get("status") == "passed"
        and evidence.get("sourceId") == source_id
        and evidence.get("clockBindingVersion") == profile.get("auditClockBindingVersion")
        and evidence.get("sourceCommit") == profile.get("sourceBaselineCommit")
        and isinstance(evidence.get("offsetMs"), int)
        and not isinstance(evidence.get("offsetMs"), bool)
        and abs(evidence["offsetMs"]) <= 100
        and observed is not None and fresh is not None and observed < fresh
    )
    bundle_path = evidence_path.with_suffix(evidence_path.suffix + ".sigstore.json")
    if not valid or not bundle_path.is_file() \
            or not _clock_signature_verified(evidence_path, bundle_path):
        return [f"IDENTITY_CLOCK_EVIDENCE_INVALID: {relative}"]
    return []


def _clock_signature_verified(evidence_path: Path, bundle_path: Path) -> bool:
    configured = os.environ.get("COSIGN_BIN", "cosign")
    cosign = shutil.which(configured)
    if cosign is None:
        return False
    command = [
        cosign, "verify-blob",
        "--bundle", str(bundle_path),
        "--certificate-identity",
        "https://github.com/keliihall/ScholarSense-bmad-method/.github/workflows/platform-probe.yml@refs/heads/main",
        "--certificate-oidc-issuer", "https://token.actions.githubusercontent.com",
        str(evidence_path),
    ]
    try:
        return subprocess.run(
            command, check=False, capture_output=True, text=True, timeout=30).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def _utc_instant(value: Any) -> datetime | None:
    if not isinstance(value, str) or not re.fullmatch(
            r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z", value):
        return None
    try:
        return datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError:
        return None


def _catalog_issues(root: Path) -> list[str]:
    try:
        catalog = load_json(root / "contracts/identity/examples/negative-catalog.json")
        cases = catalog["cases"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["IDENTITY_NEGATIVE_CATALOG_INVALID"]
    required = {
        "host-origin-forbidden", "host-source-forbidden", "host-message-unknown-field",
        "host-event-unknown", "host-message-expired", "host-nonce-replayed",
        "host-message-replayed", "host-bootstrap-already-used", "continuation-open-redirect",
        "continuation-expired", "csrf-invalid", "identity-session-version-conflict",
        "idempotency-key-mismatch",
    }
    identities = [case.get("id") for case in cases if isinstance(case, dict)]
    if catalog.get("version") != "IDENTITY-NEGATIVE-CATALOG-1.0.0" \
            or len(identities) != len(set(identities)) or set(identities) != required:
        return ["IDENTITY_NEGATIVE_CATALOG_INVALID"]
    return []


def _lock_issues(root: Path) -> list[str]:
    try:
        lock = load_json(root / LOCK)
        entries = lock["files"]
    except (OSError, ValueError, KeyError, TypeError):
        return ["IDENTITY_CONTRACT_LOCK_INVALID"]
    issues: list[str] = []
    if lock.get("version") != "IDENTITY-CONTRACT-LOCK-1.0.0" \
            or lock.get("sourceBaselineCommit") != "a0c8a9cba10d963c41623d27a8480dbbbddea393":
        issues.append("IDENTITY_CONTRACT_LOCK_INVALID")
    expected_paths = sorted(
        str(path.relative_to(root))
        for base in (root / "contracts/host", root / "contracts/identity")
        for path in base.rglob("*")
        if path.is_file() and path != root / LOCK
    )
    locked_paths = [entry.get("path") for entry in entries if isinstance(entry, dict)]
    if locked_paths != expected_paths:
        issues.append("IDENTITY_CONTRACT_LOCK_SCOPE_MISMATCH")
    for entry in entries:
        if not isinstance(entry, dict):
            issues.append("IDENTITY_CONTRACT_LOCK_INVALID")
            continue
        relative = entry.get("path")
        digest = entry.get("sha256")
        if not isinstance(relative, str) or not isinstance(digest, str):
            issues.append("IDENTITY_CONTRACT_LOCK_INVALID")
            continue
        try:
            actual = hashlib.sha256((root / relative).read_bytes()).hexdigest()
        except OSError:
            issues.append(f"IDENTITY_CONTRACT_LOCK_PATH_MISSING: {relative}")
            continue
        if actual != digest:
            issues.append(f"IDENTITY_CONTRACT_LOCK_DIGEST_MISMATCH: {relative}")
    return issues


def _contains_sensitive_key(value: Any) -> bool:
    return any(SENSITIVE.search(key) for key in _all_keys(value))


def _all_keys(value: Any) -> list[str]:
    keys: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            keys.append(str(key))
            keys.extend(_all_keys(child))
    elif isinstance(value, list):
        for child in value:
            keys.extend(_all_keys(child))
    return keys


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) == 2 else Path(".")
    issues = validate(root)
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print(f"identity-contracts: PASS ({len(SCHEMAS)} examples)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
