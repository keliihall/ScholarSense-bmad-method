#!/usr/bin/env python3
"""Run 17 source conformance probes after signed, anti-rollback, SSRF-safe preflight."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import http.client
import ipaddress
import json
import os
import socket
import ssl
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_data_catalog_contracts import EXPECTED_SOURCES, canonical_digest  # noqa: E402
from release_json import canonical_bytes, load_json, schema_issues  # noqa: E402


MAX_RESPONSE_BYTES = 1_048_576
Resolver = Callable[[str, int], set[str]]
Connector = Callable[[str, str, str | None], dict[str, Any]]


@dataclass(frozen=True)
class TargetPreflight:
    handoff: dict[str, Any]
    networks: tuple[ipaddress.IPv4Network | ipaddress.IPv6Network, ...]
    catalog_digest: str
    quality_gate_digest: str
    schema_versions: dict[str, str]


def _instant(value: str) -> datetime:
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("DCC_HANDOFF_TIME_OFFSET_REQUIRED")
    return result.astimezone(timezone.utc)


def sign_handoff(document: dict[str, Any], key: bytes) -> str:
    unsigned = {field: value for field, value in document.items() if field != "signature"}
    return "hmac-sha256:" + hmac.new(key, canonical_bytes(unsigned), hashlib.sha256).hexdigest()


def preflight_handoff(
    handoff: dict[str, Any], project_root: Path, *, authority: str, environment: str,
    candidate_commit: str, candidate_tree: str, minimum_revision: int,
    now: datetime, trusted_signing_key: bytes,
) -> TargetPreflight:
    root = project_root.resolve()
    schema = load_json(root / "contracts/data-catalog/data-catalog-target-handoff.schema.json")
    if schema_issues(handoff, schema):
        raise ValueError("DCC_HANDOFF_SCHEMA_INVALID")
    if handoff.get("authority") != authority or handoff.get("environment") != environment:
        raise ValueError("DCC_HANDOFF_SUBJECT_MISMATCH")
    if handoff.get("candidateCommit") != candidate_commit or handoff.get("candidateTree") != candidate_tree:
        raise ValueError("DCC_HANDOFF_CANDIDATE_MISMATCH")
    catalog = load_json(root / "contracts/data-catalog/dcc-1.0.0.json")
    quality_gate = load_json(root / "contracts/data-catalog/qg-1.0.0.json")
    catalog_digest = canonical_digest(catalog)
    if handoff.get("expectedCatalogDigest") != catalog_digest:
        raise ValueError("DCC_HANDOFF_DIGEST_MISMATCH")
    issued = _instant(handoff["issuedAt"])
    expires = _instant(handoff["expiresAt"])
    normalized_now = now.astimezone(timezone.utc)
    if issued > normalized_now or expires <= normalized_now or expires - issued > timedelta(hours=24):
        raise ValueError("DCC_HANDOFF_EXPIRED")
    if handoff.get("revision", 0) < minimum_revision:
        raise ValueError("DCC_HANDOFF_REVISION_ROLLBACK")
    expected_signature = sign_handoff(handoff, trusted_signing_key)
    if not hmac.compare_digest(str(handoff.get("signature", "")), expected_signature):
        raise ValueError("DCC_HANDOFF_SIGNATURE_INVALID")
    endpoints = handoff.get("sourceEndpoints", {})
    if set(endpoints) != set(EXPECTED_SOURCES):
        raise ValueError("DCC_HANDOFF_SOURCE_SET_INVALID")
    approved_hosts = {host.lower() for host in handoff["approvedHosts"]}
    networks = tuple(ipaddress.ip_network(value, strict=True) for value in handoff["approvedIpCidrs"])
    for endpoint in endpoints.values():
        parsed = urlsplit(endpoint)
        if parsed.scheme != "https" or parsed.hostname is None or parsed.hostname.lower() not in approved_hosts:
            raise ValueError("DCC_ENDPOINT_AUTHORITY_INVALID")
        if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.port not in (None, 443):
            raise ValueError("DCC_ENDPOINT_URL_INVALID")
    schema_versions = {
        item["sourceId"]: item["schemaVersion"] for item in catalog["sources"]
    }
    return TargetPreflight(
        handoff, networks, catalog_digest, canonical_digest(quality_gate),
        schema_versions,
    )


def _system_resolver(host: str, port: int) -> set[str]:
    return {item[4][0] for item in socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)}


def resolve_approved(endpoint: str, preflight: TargetPreflight, resolver: Resolver) -> tuple[str, str]:
    parsed = urlsplit(endpoint)
    host = parsed.hostname or ""
    first = resolver(host, 443)
    second = resolver(host, 443)
    if not first or first != second:
        raise ValueError("DCC_ENDPOINT_DNS_REBINDING")
    for value in first:
        address = ipaddress.ip_address(value)
        if not any(address in network for network in preflight.networks):
            raise ValueError("DCC_ENDPOINT_IP_NOT_APPROVED")
    return host, sorted(first)[0]


def _fetch(endpoint: str, pinned_ip: str, token: str | None) -> dict[str, Any]:
    parsed = urlsplit(endpoint)
    host = parsed.hostname or ""
    context = ssl.create_default_context()
    raw = socket.create_connection((pinned_ip, 443), timeout=15)
    try:
        tls = context.wrap_socket(raw, server_hostname=host)
        connection = http.client.HTTPConnection(host, 443, timeout=15)
        connection.sock = tls
        headers = {"Accept": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        connection.request("GET", parsed.path or "/", headers=headers)
        response = connection.getresponse()
        length = response.getheader("Content-Length")
        if length is not None and int(length) > MAX_RESPONSE_BYTES:
            raise ValueError("DCC_TARGET_RESPONSE_TOO_LARGE")
        body = response.read(MAX_RESPONSE_BYTES + 1)
        if len(body) > MAX_RESPONSE_BYTES:
            raise ValueError("DCC_TARGET_RESPONSE_TOO_LARGE")
        if response.status != 200:
            raise ValueError("DCC_TARGET_HTTP_FAILED")
        return json.loads(body)
    finally:
        raw.close()


def execute_target(
    preflight: TargetPreflight, *, resolver: Resolver = _system_resolver,
    connector: Connector = _fetch, token: str | None = None,
    occurred_at: datetime | None = None,
) -> dict[str, Any]:
    observed_at = (occurred_at or datetime.now(timezone.utc)).astimezone(timezone.utc)
    occurred_at_value = observed_at.replace(microsecond=0).isoformat().replace("+00:00", "Z")
    signature_digest = "sha256:" + hashlib.sha256(
        str(preflight.handoff["signature"]).encode("utf-8")
    ).hexdigest()
    summaries: list[dict[str, Any]] = []
    for source_id in sorted(EXPECTED_SOURCES):
        endpoint = preflight.handoff["sourceEndpoints"][source_id]
        _, pinned_ip = resolve_approved(endpoint, preflight, resolver)
        response = connector(endpoint, pinned_ip, token)
        if (set(response) != {"sourceId", "inputDigest", "scenarios", "cleanupResult"}
                or response.get("sourceId") != source_id
                or not str(response.get("inputDigest", "")).startswith("sha256:")
                or response.get("cleanupResult") != "pass"
                or not isinstance(response.get("scenarios"), list)
                or not response["scenarios"]
                or any(item.get("result") != "pass" for item in response["scenarios"])):
            raise ValueError("DCC_TARGET_SCENARIO_FAILED")
        source_evidence = {
            "sourceId": source_id,
            "contractVersion": "DCC-1.0.0",
            "schemaVersion": preflight.schema_versions[source_id],
            "qualityGateVersion": "QG-1.0.0",
            "environment": preflight.handoff["environment"],
            "authority": preflight.handoff["authority"],
            "candidateCommit": preflight.handoff["candidateCommit"],
            "candidateTree": preflight.handoff["candidateTree"],
            "inputDigest": response["inputDigest"],
            "scenarios": response["scenarios"],
            "result": "pass",
            "occurredAt": occurred_at_value,
            "signatureDigest": signature_digest,
            "cleanupResult": "pass",
            "runtimeEvidenceClaim": "target-verified",
        }
        source_evidence["evidenceDigest"] = evidence_digest(source_evidence)
        summaries.append(source_evidence)
    report = {
        "reportVersion": "DCC-TARGET-REPORT-1.0.0",
        "contractVersion": "DCC-1.0.0",
        "qualityGateVersion": "QG-1.0.0",
        "authority": preflight.handoff["authority"],
        "environment": preflight.handoff["environment"],
        "candidateCommit": preflight.handoff["candidateCommit"],
        "candidateTree": preflight.handoff["candidateTree"],
        "catalogDigest": preflight.catalog_digest,
        "qualityGateDigest": preflight.quality_gate_digest,
        "sourceCount": len(summaries),
        "sources": summaries,
        "privacy": {"studentPlaintextStored": False, "rawBodyStored": False},
        "occurredAt": occurred_at_value,
        "runtimeEvidenceClaim": "target-verified",
        "result": "pass",
    }
    report["evidenceDigest"] = evidence_digest(report)
    return report


def evidence_digest(document: dict[str, Any]) -> str:
    unsigned = {field: value for field, value in document.items() if field != "evidenceDigest"}
    return "sha256:" + hashlib.sha256(canonical_bytes(unsigned)).hexdigest()


def validate_report(report: Any, project_root: Path) -> list[str]:
    root = project_root.resolve()
    if not isinstance(report, dict):
        return ["DCC_TARGET_REPORT_INVALID"]
    schema = load_json(root / "contracts/data-catalog/data-catalog-target-report.schema.json")
    source_schema = load_json(root / "contracts/data-catalog/data-catalog-runtime-evidence.schema.json")
    issues = list(schema_issues(report, schema))
    raw_sources = report.get("sources", [])
    sources = raw_sources if isinstance(raw_sources, list) else []
    source_ids = {
        item.get("sourceId") for item in sources if isinstance(item, dict)
    }
    catalog = load_json(root / "contracts/data-catalog/dcc-1.0.0.json")
    quality_gate = load_json(root / "contracts/data-catalog/qg-1.0.0.json")
    if source_ids != set(EXPECTED_SOURCES) or len(sources) != len(EXPECTED_SOURCES):
        issues.append("DCC_TARGET_SOURCE_SET_INVALID")
    if (
        report.get("sourceCount") != 17
        or report.get("catalogDigest") != canonical_digest(catalog)
        or report.get("qualityGateDigest") != canonical_digest(quality_gate)
        or report.get("runtimeEvidenceClaim") != "target-verified"
        or report.get("result") != "pass"
        or report.get("evidenceDigest") != evidence_digest(report)
        or report.get("privacy") != {
            "studentPlaintextStored": False, "rawBodyStored": False
        }
    ):
        issues.append("DCC_TARGET_REPORT_SEMANTICS_INVALID")
    for source in sources:
        issues.extend(schema_issues(source, source_schema))
        scenarios = source.get("scenarios", []) if isinstance(source, dict) else []
        if not isinstance(source, dict) or (
            source.get("candidateCommit") != report.get("candidateCommit")
            or source.get("candidateTree") != report.get("candidateTree")
            or source.get("authority") != report.get("authority")
            or source.get("environment") != report.get("environment")
            or source.get("result") != "pass"
            or source.get("cleanupResult") != "pass"
            or source.get("runtimeEvidenceClaim") != "target-verified"
            or source.get("evidenceDigest") != evidence_digest(source)
            or not isinstance(scenarios, list)
            or not scenarios
            or any(
                not isinstance(item, dict) or item.get("result") != "pass"
                for item in scenarios
            )
        ):
            issues.append("DCC_TARGET_SOURCE_EVIDENCE_INVALID")
    return sorted(set(issues))


def _git(root: Path, *arguments: str) -> str:
    return subprocess.run(["git", *arguments], cwd=root, check=True, text=True, capture_output=True).stdout.strip()


def candidate_identity(root: Path) -> tuple[str, str]:
    if _git(root, "status", "--porcelain", "--untracked-files=all"):
        raise ValueError("DCC_TARGET_CANDIDATE_DIRTY")
    return _git(root, "rev-parse", "HEAD"), _git(root, "rev-parse", "HEAD^{tree}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--handoff", required=True)
    parser.add_argument("--trusted-signing-key", required=True)
    parser.add_argument("--authority", required=True)
    parser.add_argument("--environment", required=True, choices=("test", "stage", "prod"))
    parser.add_argument("--minimum-revision", required=True, type=int)
    parser.add_argument("--report", required=True)
    parser.add_argument("--project-root", default=".")
    args = parser.parse_args(argv)
    root = Path(args.project_root).resolve()
    try:
        handoff = load_json(Path(args.handoff))
        signing_key = Path(args.trusted_signing_key).read_bytes()
        candidate_commit, candidate_tree = candidate_identity(root)
        preflight = preflight_handoff(
            handoff, root, authority=args.authority, environment=args.environment,
            candidate_commit=candidate_commit, candidate_tree=candidate_tree,
            minimum_revision=args.minimum_revision, now=datetime.now(timezone.utc),
            trusted_signing_key=signing_key,
        )
        # Endpoint credentials are intentionally read only after signed handoff preflight succeeds.
        report = execute_target(preflight, token=os.environ.get("DATA_CATALOG_TARGET_TOKEN"))
        issues = validate_report(report, root)
        if issues:
            raise ValueError(issues[0])
        Path(args.report).write_bytes(canonical_bytes(report) + b"\n")
    except (OSError, ValueError, TypeError, KeyError, subprocess.CalledProcessError) as error:
        print(f"data-catalog-target: FAIL ({error})", file=sys.stderr)
        return 1
    print("data-catalog-target: PASS (sources=17; runtimeEvidenceClaim=target-verified)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
