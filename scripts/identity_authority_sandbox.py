#!/usr/bin/env python3
"""Controlled local identity-authority sandbox for Story 1.6a runtime evidence."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import hmac
import json
import re
import signal
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


SOURCE_ID = "SRC-P0-RESPONSIBILITY-001"
MAPPING_VERSION = "IDENTITY-ROLE-MAPPING-1.0.0"
MAPPING_DIGEST = (
    "sha256:f09768f88cd6a595791ec6591e65758b85fd8402585c8ffaa053446214895e29"
)
ZERO_SIGNATURE_DIGEST = "sha256:" + ("0" * 64)
TRACE_PATTERN = re.compile(r"^[0-9a-f]{32}$")
BASE_TIME = dt.datetime.now(dt.UTC).replace(microsecond=0) - dt.timedelta(minutes=5)


def timestamp(offset_seconds: int) -> str:
    return (BASE_TIME + dt.timedelta(seconds=offset_seconds)).isoformat().replace(
        "+00:00", "Z"
    )


def canonical(value: object) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def uuid7(watermark: int, suffix: int) -> str:
    return f"019d0000-{watermark:04x}-7000-8000-{suffix:012x}"


def record(
    watermark: int,
    index: int,
    kind: str,
    payload: dict[str, object],
    *,
    effective_to: str | None = None,
) -> dict[str, object]:
    effective_from = timestamp(watermark)
    return {
        "eventId": uuid7(watermark, 100 + index),
        "recordKind": kind,
        "sourceVersion": watermark,
        "effectiveFrom": effective_from,
        "effectiveTo": effective_to,
        "payloadDigest": "sha256:" + digest(canonical(payload)),
        "payload": payload,
    }


def organization(
    watermark: int,
    external_ref: str,
    parent: str | None,
    display_name: str,
    organization_type: str,
    *,
    status: str = "active",
) -> dict[str, object]:
    return {
        "externalRef": external_ref,
        "parentExternalRef": parent,
        "displayName": display_name,
        "organizationType": organization_type,
        "status": status,
        "effectiveFrom": timestamp(watermark),
        "effectiveTo": None,
        "recordVersion": watermark,
    }


def account(
    watermark: int,
    external_ref: str,
    subject: str,
    *,
    status: str = "active",
) -> dict[str, object]:
    return {
        "externalRef": external_ref,
        "issuer": "https://idp.sandbox.invalid",
        "subject": subject,
        "status": status,
        "effectiveFrom": timestamp(watermark),
        "effectiveTo": None,
        "recordVersion": watermark,
    }


def role(
    watermark: int,
    external_ref: str,
    account_ref: str,
    organization_ref: str,
    source_role: str,
    target_role: str,
    *,
    status: str = "active",
    effective_to: str | None = None,
) -> dict[str, object]:
    return {
        "externalRef": external_ref,
        "accountExternalRef": account_ref,
        "organizationExternalRef": organization_ref,
        "sourceRoleCode": source_role,
        "targetRoleId": target_role,
        "mappingVersion": MAPPING_VERSION,
        "status": status,
        "effectiveFrom": timestamp(watermark),
        "effectiveTo": effective_to,
        "recordVersion": watermark,
    }


def scenario(after_watermark: int) -> tuple[str, list[dict[str, object]]]:
    watermark = after_watermark + 1
    school_status = "inactive" if after_watermark == 8 else "active"
    department_parent = "ORG-SCHOOL" if after_watermark == 7 else "ORG-COLLEGE"
    department_name = (
        "计算机科学与技术系（更名）"
        if after_watermark == 6
        else "计算机科学与技术系"
    )
    organizations = [
        organization(
            watermark, "ORG-SCHOOL", None, "苏州大学", "school", status=school_status
        ),
        organization(
            watermark, "ORG-COLLEGE", "ORG-SCHOOL", "计算机学院", "college"
        ),
    ]
    if after_watermark >= 5:
        organizations.append(
            organization(
                watermark,
                "ORG-DEPARTMENT",
                department_parent,
                department_name,
                "department",
            )
        )

    subject = "subject-001-v2" if after_watermark >= 1 else "subject-001"
    account_status = "inactive" if after_watermark == 2 else "active"
    accounts = [account(watermark, "ACCOUNT-001", subject, status=account_status)]
    roles = [
        role(
            watermark,
            "EMPLOYMENT-001",
            "ACCOUNT-001",
            "ORG-COLLEGE",
            "SANDBOX_COUNSELOR",
            "R1-COUNSELOR",
        )
    ]

    name = {
        0: "account-added",
        1: "account-changed",
        2: "account-deactivated",
        3: "role-added-and-multi-role",
        4: "role-removed",
        5: "organization-added",
        6: "organization-renamed",
        7: "organization-reparented",
        8: "organization-deactivated",
        9: "employment-window-ended",
        10: "unknown-role",
        11: "subject-binding-conflict",
        12: "invalid-signature",
    }.get(after_watermark, "unsupported")

    if after_watermark in {3, 4}:
        roles.append(
            role(
                watermark,
                "EMPLOYMENT-002",
                "ACCOUNT-001",
                "ORG-COLLEGE",
                "SANDBOX_DATA_OWNER",
                "R6-DATA-OWNER",
                status="inactive" if after_watermark == 4 else "active",
                effective_to=(
                    timestamp(300) if after_watermark == 4 else None
                ),
            )
        )
    if after_watermark in {5, 6, 7, 8}:
        roles.append(
            role(
                watermark,
                "EMPLOYMENT-003",
                "ACCOUNT-001",
                "ORG-DEPARTMENT",
                "SANDBOX_COLLABORATOR",
                "R5-COLLABORATOR",
                status="inactive" if after_watermark == 8 else "active",
            )
        )
    if after_watermark == 9:
        roles[0] = role(
            watermark,
            "EMPLOYMENT-001",
            "ACCOUNT-001",
            "ORG-COLLEGE",
            "SANDBOX_COUNSELOR",
            "R1-COUNSELOR",
            status="inactive",
            effective_to=timestamp(570),
        )
    if after_watermark == 10:
        roles[0] = role(
            watermark,
            "EMPLOYMENT-001",
            "ACCOUNT-001",
            "ORG-COLLEGE",
            "SANDBOX_UNKNOWN",
            "R1-COUNSELOR",
        )
    if after_watermark == 11:
        accounts.append(account(watermark, "ACCOUNT-002", subject))

    values: list[dict[str, object]] = []
    index = 1
    for value in organizations:
        values.append(record(watermark, index, "organization", value))
        index += 1
    for value in accounts:
        values.append(record(watermark, index, "account", value))
        index += 1
    for value in roles:
        effective_to = value["effectiveTo"]
        values.append(
            record(
                watermark,
                index,
                "employment-role",
                value,
                effective_to=effective_to if isinstance(effective_to, str) else None,
            )
        )
        index += 1
    return name, values


def signed_batch(
    after_watermark: int, trace_id: str, signature_key: bytes
) -> tuple[bytes, str, str]:
    name, records = scenario(after_watermark)
    watermark = after_watermark + 1
    batch: dict[str, object] = {
        "schemaVersion": "IDENTITY-AUTHORITY-BATCH-1.0.0",
        "resultType": "changes",
        "sourceId": SOURCE_ID,
        "feedId": "identity-authority",
        "partitionId": "sandbox-0",
        "consumerProjection": "identity-org",
        "batchId": uuid7(watermark, 1),
        "sourceVersion": watermark,
        "fromWatermark": after_watermark,
        "toWatermark": watermark,
        "sourceVisibleAt": timestamp(watermark),
        "observedAt": timestamp(600),
        "traceId": trace_id,
        "correlationId": uuid7(watermark, 2),
        "mappingVersion": MAPPING_VERSION,
        "mappingDigest": MAPPING_DIGEST,
        "signatureDigest": ZERO_SIGNATURE_DIGEST,
        "records": records,
    }
    unsigned = canonical(batch)
    signature = hmac.new(signature_key, unsigned, hashlib.sha256).hexdigest()
    if after_watermark == 12:
        signature = "f" * 64
    batch["signatureDigest"] = "sha256:" + digest(signature.encode("utf-8"))
    return canonical(batch), signature, name


class SandboxState:
    def __init__(
        self, token: str, signature_key: bytes, trace_file: Path
    ) -> None:
        self.token = token
        self.signature_key = signature_key
        self.trace_file = trace_file
        self.lock = threading.Lock()
        self.events: list[dict[str, object]] = []

    def append(self, event: dict[str, object]) -> None:
        with self.lock:
            self.events.append(event)
            self.trace_file.write_text(
                json.dumps(
                    {"events": self.events},
                    ensure_ascii=False,
                    sort_keys=True,
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )


class Handler(BaseHTTPRequestHandler):
    server_version = "ScholarSenseIdentitySandbox/1.0"

    def do_GET(self) -> None:  # noqa: N802
        state: SandboxState = self.server.state  # type: ignore[attr-defined]
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        trace_id = self.headers.get("X-ScholarSense-Trace-Id", "")
        after = query.get("afterWatermark", [""])[0]
        projection = query.get("consumerProjection", [""])[0]
        authorization = self.headers.get("Authorization", "")

        if parsed.path != "/api/v1/incremental":
            self._respond(404, b"")
            return
        if not hmac.compare_digest(authorization, f"Bearer {state.token}"):
            self._respond(401, b"")
            state.append(
                {
                    "scenario": "authentication-failure",
                    "traceId": trace_id if TRACE_PATTERN.fullmatch(trace_id) else "invalid",
                    "afterWatermark": int(after) if after.isdigit() else -1,
                    "httpStatus": 401,
                    "authorization": "rejected",
                    "responseSha256": None,
                    "signature": "not-issued",
                }
            )
            return
        if (
            not after.isdigit()
            or int(after) > 12
            or projection != "identity-org"
            or not TRACE_PATTERN.fullmatch(trace_id)
        ):
            self._respond(400, b"")
            return

        body, signature, name = signed_batch(
            int(after), trace_id, state.signature_key
        )
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-Identity-Authority-Signature", signature)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        state.append(
            {
                "scenario": name,
                "traceId": trace_id,
                "afterWatermark": int(after),
                "httpStatus": 200,
                "authorization": "accepted",
                "responseSha256": digest(body),
                "signature": "invalid" if int(after) == 12 else "valid",
            }
        )

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def _respond(self, status: int, body: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--ready-file", type=Path, required=True)
    parser.add_argument("--trace-file", type=Path, required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--signature-key", required=True)
    args = parser.parse_args()

    args.ready_file.parent.mkdir(parents=True, exist_ok=True)
    args.trace_file.parent.mkdir(parents=True, exist_ok=True)
    state = SandboxState(
        args.token, args.signature_key.encode("utf-8"), args.trace_file
    )
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.state = state  # type: ignore[attr-defined]
    endpoint = (
        f"http://127.0.0.1:{server.server_address[1]}/api/v1/incremental"
    )
    args.ready_file.write_text(endpoint + "\n", encoding="utf-8")

    def stop(_signum: int, _frame: object) -> None:
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    server.serve_forever(poll_interval=0.1)
    server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
