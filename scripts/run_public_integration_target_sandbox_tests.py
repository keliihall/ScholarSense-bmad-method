#!/usr/bin/env python3
"""Fail-closed PIC target handoff preflight and non-production runner."""

from __future__ import annotations

import argparse
import base64
import dataclasses
import datetime as dt
import hashlib
import hmac
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import socket
import ssl
import subprocess
import tempfile
import threading
from typing import Any, Callable
from urllib.parse import urlsplit

from scripts import run_public_integration_sandbox_tests as local_runner


canonical_json_bytes = local_runner.canonical_json_bytes
ROOT = Path(__file__).resolve().parents[1]
TARGET_SCENARIOS = (
    ROOT
    / "contracts/public-integration/public-integration-target-scenarios-1.0.0.json"
)


REQUIRED_ENV = (
    "SCHOLARSENSE_PIC_TARGET_HANDOFF_FILE",
    "SCHOLARSENSE_PIC_TARGET_HANDOFF_SIGNATURE_FILE",
    "SCHOLARSENSE_PIC_TARGET_HANDOFF_TRUST_ROOT_FILE",
    "SCHOLARSENSE_PIC_TARGET_HANDOFF_EXPECTED_SHA256",
)
CAPABILITIES = [
    "public-task",
    "public-message",
    "status-result-writeback",
    "external-transfer-work-order",
    "metric-publication",
]
HANDOFF_FIELDS = {
    "handoffVersion",
    "handoffId",
    "revision",
    "previousRevisionDigest",
    "issuedAt",
    "notBefore",
    "expiresAt",
    "signerKeyId",
    "authority",
    "environment",
    "trustAnchorDigest",
    "peerSpkiSha256",
    "capabilities",
    "protocols",
    "credentialRef",
    "targetDescriptor",
}
SIGNATURE_FIELDS = {
    "signatureVersion",
    "algorithm",
    "keyId",
    "canonicalizationProfile",
    "handoffSha256",
    "signatureBase64",
}
HEX64 = re.compile(r"^[0-9a-f]{64}$")
HEX40 = re.compile(r"^[0-9a-f]{40}$")
UUID7 = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)


@dataclasses.dataclass(frozen=True)
class TargetPreflightResult:
    handoff: dict[str, Any]
    handoff_digest: str
    signature_verified: bool
    trust_root_digest: str
    credentials: Any
    network_binding: dict[str, str] | None


def certificate_spki_sha256(certificate: str | Path) -> str:
    """Return the SHA-256 of a certificate's DER SubjectPublicKeyInfo."""
    public_key = subprocess.run(
        ["openssl", "x509", "-in", str(certificate), "-pubkey", "-noout"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if public_key.returncode != 0:
        raise ValueError("PIC_TARGET_CERTIFICATE_INVALID")
    spki = subprocess.run(
        ["openssl", "pkey", "-pubin", "-outform", "DER"],
        input=public_key.stdout,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if spki.returncode != 0:
        raise ValueError("PIC_TARGET_CERTIFICATE_PUBLIC_KEY_INVALID")
    return hashlib.sha256(spki.stdout).hexdigest()


def _certificate_der_spki_sha256(certificate_der: bytes) -> str:
    with tempfile.TemporaryDirectory(prefix="scholarsense-pic-peer-") as directory:
        certificate = Path(directory) / "peer.pem"
        certificate.write_text(
            ssl.DER_cert_to_PEM_cert(certificate_der), encoding="ascii"
        )
        return certificate_spki_sha256(certificate)


def _verified_chain_sha256(chain: list[bytes]) -> str:
    digest = hashlib.sha256()
    digest.update(b"PIC-TLS-VERIFIED-CHAIN-1.0.0\0")
    for certificate in chain:
        digest.update(len(certificate).to_bytes(4, byteorder="big"))
        digest.update(certificate)
    return digest.hexdigest()


def _tls_peer_binding(tls_socket: ssl.SSLSocket) -> dict[str, str]:
    chain = tls_socket.get_verified_chain()
    if not chain or not all(isinstance(item, bytes) and item for item in chain):
        raise ValueError("PIC_TARGET_VERIFIED_CERTIFICATE_CHAIN_MISSING")
    return {
        "peerSpkiSha256": _certificate_der_spki_sha256(chain[0]),
        "peerChainSha256": _verified_chain_sha256(chain),
    }


def _instant(value: str) -> dt.datetime:
    parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("PIC_TARGET_TIMEZONE_REQUIRED")
    return parsed.astimezone(dt.UTC)


def _normalized_expected(value: str) -> str:
    return value.removeprefix("sha256:")


def _validate_handoff(document: Any, *, now: str) -> None:
    if not isinstance(document, dict) or set(document) != HANDOFF_FIELDS:
        raise ValueError("PIC_TARGET_HANDOFF_SHAPE_INVALID")
    if document["handoffVersion"] != "PIC-TARGET-HANDOFF-1.0.0":
        raise ValueError("PIC_TARGET_HANDOFF_VERSION_INVALID")
    if not UUID7.fullmatch(str(document["handoffId"])):
        raise ValueError("PIC_TARGET_HANDOFF_ID_INVALID")
    revision = document["revision"]
    if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
        raise ValueError("PIC_TARGET_HANDOFF_REVISION_INVALID")
    previous = document["previousRevisionDigest"]
    if revision == 1 and previous is not None:
        raise ValueError("PIC_TARGET_HANDOFF_PREVIOUS_REVISION_INVALID")
    if revision > 1 and (
        not isinstance(previous, str)
        or not re.fullmatch(r"sha256:[0-9a-f]{64}", previous)
    ):
        raise ValueError("PIC_TARGET_HANDOFF_PREVIOUS_REVISION_INVALID")
    issued = _instant(document["issuedAt"])
    not_before = _instant(document["notBefore"])
    expires = _instant(document["expiresAt"])
    trusted_now = _instant(now)
    if not (issued <= not_before < expires):
        raise ValueError("PIC_TARGET_HANDOFF_TIME_ORDER_INVALID")
    if expires - not_before > dt.timedelta(hours=24):
        raise ValueError("PIC_TARGET_HANDOFF_VALIDITY_TOO_LONG")
    if not (not_before <= trusted_now <= expires):
        raise ValueError("PIC_TARGET_HANDOFF_NOT_CURRENT")
    if document["environment"] != "non-production-sandbox":
        raise ValueError("PIC_TARGET_ENVIRONMENT_INVALID")
    for field in ("trustAnchorDigest", "peerSpkiSha256"):
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", str(document[field])):
            raise ValueError(f"PIC_TARGET_{field.upper()}_INVALID")
    if sorted(document["capabilities"]) != sorted(CAPABILITIES):
        raise ValueError("PIC_TARGET_CAPABILITIES_INVALID")
    protocols = document["protocols"]
    expected_protocols = {
        "receipt": "PIC-RECEIPT-1.0.0",
        "challenge": "PIC-CHALLENGE-1.0.0",
        "sandboxCleanup": "PIC-SANDBOX-CLEANUP-1.0.0",
        "terminalByStableWorkItemKey": True,
        "rejectStaleSourceVersion": True,
    }
    if protocols != expected_protocols:
        raise ValueError("PIC_TARGET_PROTOCOLS_INVALID")
    if not re.fullmatch(
        r"credential-ref:[A-Za-z0-9._/-]{1,256}", str(document["credentialRef"])
    ):
        raise ValueError("PIC_TARGET_CREDENTIAL_REF_INVALID")
    descriptor = document["targetDescriptor"]
    if (
        not isinstance(descriptor, dict)
        or set(descriptor) != {"descriptorVersion", "baseUri"}
        or descriptor["descriptorVersion"] != "PIC-TARGET-DESCRIPTOR-1.0.0"
        or not re.fullmatch(r"https://[^\s?#]+(?:/[^\s?#]*)?", str(descriptor["baseUri"]))
    ):
        raise ValueError("PIC_TARGET_DESCRIPTOR_INVALID")


def _validate_signature(document: Any, handoff: dict[str, Any], digest: str) -> bytes:
    if not isinstance(document, dict) or set(document) != SIGNATURE_FIELDS:
        raise ValueError("PIC_TARGET_SIGNATURE_SHAPE_INVALID")
    if (
        document["signatureVersion"] != "PIC-TARGET-HANDOFF-SIGNATURE-1.0.0"
        or document["algorithm"] != "Ed25519"
        or document["canonicalizationProfile"]
        != "SCHOLARSENSE-CANONICAL-JSON-1.0.0"
    ):
        raise ValueError("PIC_TARGET_SIGNATURE_PROFILE_INVALID")
    if document["keyId"] != handoff["signerKeyId"]:
        raise ValueError("PIC_TARGET_SIGNATURE_KEY_MISMATCH")
    if document["handoffSha256"] != digest:
        raise ValueError("PIC_TARGET_SIGNATURE_DIGEST_MISMATCH")
    try:
        raw = base64.b64decode(document["signatureBase64"], validate=True)
    except (ValueError, TypeError) as failure:
        raise ValueError("PIC_TARGET_SIGNATURE_ENCODING_INVALID") from failure
    if len(raw) != 64:
        raise ValueError("PIC_TARGET_SIGNATURE_LENGTH_INVALID")
    return raw


def _openssl_verify(message: bytes, signature: bytes, trust_root: Path) -> bool:
    with tempfile.TemporaryDirectory(prefix="scholarsense-pic-signature-") as directory:
        root = Path(directory)
        message_file = root / "handoff.canonical.json"
        signature_file = root / "handoff.signature"
        message_file.write_bytes(message)
        signature_file.write_bytes(signature)
        completed = subprocess.run(
            [
                "openssl",
                "pkeyutl",
                "-verify",
                "-pubin",
                "-rawin",
                "-inkey",
                str(trust_root),
                "-in",
                str(message_file),
                "-sigfile",
                str(signature_file),
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        return completed.returncode == 0


def _default_credential_resolver(reference: str) -> dict[str, str]:
    prefix = "credential-ref:env/"
    if not reference.startswith(prefix):
        raise ValueError("PIC_TARGET_CREDENTIAL_REF_SCHEME_UNSUPPORTED")
    variable = reference[len(prefix):]
    bundle_path = os.environ.get(variable)
    if not bundle_path:
        raise ValueError("PIC_TARGET_CREDENTIAL_BUNDLE_UNAVAILABLE")
    document = json.loads(Path(bundle_path).read_text(encoding="utf-8"))
    required = {"clientCertFile", "clientKeyFile", "caFile", "bearerToken"}
    if not isinstance(document, dict) or set(document) != required:
        raise ValueError("PIC_TARGET_CREDENTIAL_BUNDLE_INVALID")
    return document


def _validated_target_uri(base_uri: str) -> tuple[str, int, str]:
    parsed = urlsplit(base_uri)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("PIC_TARGET_NETWORK_PROBE_INPUT_INVALID")
    try:
        port = parsed.port or 443
    except ValueError as failure:
        raise ValueError("PIC_TARGET_NETWORK_PROBE_INPUT_INVALID") from failure
    return parsed.hostname, port, parsed.path.rstrip("/")


def _default_network_probe(
    base_uri: str, credentials: Any
) -> dict[str, str]:
    if not isinstance(credentials, dict):
        raise ValueError("PIC_TARGET_NETWORK_PROBE_INPUT_INVALID")
    host, port, _ = _validated_target_uri(base_uri)
    context = ssl.create_default_context(cafile=credentials["caFile"])
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(
        certfile=credentials["clientCertFile"],
        keyfile=credentials["clientKeyFile"],
    )
    try:
        with socket.create_connection((host, port), timeout=10) as raw_socket:
            with context.wrap_socket(raw_socket, server_hostname=host) as tls_socket:
                binding = _tls_peer_binding(tls_socket)
    except (OSError, ssl.SSLError) as failure:
        raise ValueError("PIC_TARGET_MTLS_PROBE_FAILED") from failure
    return binding


def preflight_target_handoff(
    environment: dict[str, str],
    *,
    now: str,
    signature_verifier: Callable[[bytes, bytes, Path], bool] | None = None,
    network_probe: Callable[..., Any] | None = None,
    credential_resolver: Callable[[str], Any] | None = None,
) -> TargetPreflightResult:
    missing = [name for name in REQUIRED_ENV if not environment.get(name)]
    if missing:
        raise ValueError("PIC_TARGET_PROTECTED_INPUT_MISSING")
    handoff_path = Path(environment[REQUIRED_ENV[0]])
    handoff_bytes = handoff_path.read_bytes()
    handoff_digest = hashlib.sha256(handoff_bytes).hexdigest()
    expected = _normalized_expected(environment[REQUIRED_ENV[3]])
    if not HEX64.fullmatch(expected) or handoff_digest != expected:
        raise ValueError("PIC_TARGET_EXPECTED_DIGEST_MISMATCH")

    handoff = json.loads(handoff_bytes)
    canonical = canonical_json_bytes(handoff)
    if handoff_bytes != canonical:
        raise ValueError("PIC_TARGET_HANDOFF_NOT_CANONICAL")
    _validate_handoff(handoff, now=now)

    signature_document = json.loads(
        Path(environment[REQUIRED_ENV[1]]).read_text(encoding="utf-8")
    )
    signature = _validate_signature(signature_document, handoff, handoff_digest)
    trust_root = Path(environment[REQUIRED_ENV[2]])
    trust_digest = hashlib.sha256(trust_root.read_bytes()).hexdigest()
    if handoff["trustAnchorDigest"] != "sha256:" + trust_digest:
        raise ValueError("PIC_TARGET_TRUST_ROOT_DIGEST_MISMATCH")
    verifier = signature_verifier or _openssl_verify
    if not verifier(canonical, signature, trust_root):
        raise ValueError("PIC_TARGET_SIGNATURE_INVALID")

    resolver = credential_resolver or _default_credential_resolver
    credentials = resolver(handoff["credentialRef"])
    probe = network_probe or _default_network_probe
    network_binding = probe(handoff["targetDescriptor"]["baseUri"], credentials)
    if network_binding is not None:
        if (
            not isinstance(network_binding, dict)
            or set(network_binding) != {"peerSpkiSha256", "peerChainSha256"}
            or not HEX64.fullmatch(str(network_binding["peerSpkiSha256"]))
            or not HEX64.fullmatch(str(network_binding["peerChainSha256"]))
        ):
            raise ValueError("PIC_TARGET_NETWORK_BINDING_INVALID")
        if handoff["peerSpkiSha256"] != (
            "sha256:" + network_binding["peerSpkiSha256"]
        ):
            raise ValueError("PIC_TARGET_PEER_SPKI_MISMATCH")
    return TargetPreflightResult(
        handoff=handoff,
        handoff_digest=handoff_digest,
        signature_verified=True,
        trust_root_digest=trust_digest,
        credentials=credentials,
        network_binding=network_binding,
    )


class DelegatedTargetSandbox:
    """Small delegated, non-production reference target with real mutual TLS."""

    def __init__(
        self,
        *,
        bind_host: str,
        advertised_host: str | None = None,
        server_cert: str,
        server_key: str,
        client_ca: str,
        bearer_token: str,
    ) -> None:
        self.bind_host = bind_host
        self.advertised_host = advertised_host or bind_host
        self.server_cert = server_cert
        self.server_key = server_key
        self.client_ca = client_ca
        self.bearer_token = bearer_token
        self.sandbox_run_id = "sandbox-" + hashlib.sha256(
            os.urandom(32)
        ).hexdigest()[:32]
        self.transient_failures_served = 0
        self._expected_index = 0
        self._transient_request_digest: str | None = None
        self._created = 0
        self._closed = 0
        self._revoked = 0
        self._server: ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None
        self.base_uri = ""

    def __enter__(self) -> "DelegatedTargetSandbox":
        owner = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *_: Any) -> None:
                return

            def do_POST(self) -> None:  # noqa: N802 - stdlib handler API
                owner._handle(self)

        class QuietThreadingHTTPServer(ThreadingHTTPServer):
            def handle_error(self, *_: Any) -> None:
                # A TLS-only preflight intentionally closes before sending HTTP.
                return

        server = QuietThreadingHTTPServer((self.bind_host, 0), Handler)
        server.daemon_threads = True
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(certfile=self.server_cert, keyfile=self.server_key)
        context.load_verify_locations(cafile=self.client_ca)
        context.verify_mode = ssl.CERT_REQUIRED
        server.socket = context.wrap_socket(server.socket, server_side=True)
        self._server = server
        port = int(server.server_address[1])
        self.base_uri = f"https://{self.advertised_host}:{port}"
        self._thread = threading.Thread(
            target=server.serve_forever,
            name="pic-delegated-target",
            daemon=True,
        )
        self._thread.start()
        return self

    def __exit__(self, *_: Any) -> None:
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
        if self._thread is not None:
            self._thread.join(timeout=10)

    @staticmethod
    def _reply(
        handler: BaseHTTPRequestHandler, status: int, document: dict[str, Any]
    ) -> None:
        body = canonical_json_bytes(document)
        handler.send_response(status)
        handler.send_header("Content-Type", "application/json")
        handler.send_header("Content-Length", str(len(body)))
        handler.send_header("Connection", "close")
        handler.end_headers()
        handler.wfile.write(body)

    def _handle(self, handler: BaseHTTPRequestHandler) -> None:
        authorization = handler.headers.get("Authorization", "")
        if not hmac.compare_digest(
            authorization, "Bearer " + self.bearer_token
        ):
            self._reply(handler, 401, {"error": "PIC_TARGET_AUTH_REQUIRED"})
            return
        try:
            length = int(handler.headers.get("Content-Length", "-1"))
        except ValueError:
            length = -1
        if length < 0 or length > 65_536:
            self._reply(handler, 413, {"error": "PIC_TARGET_BODY_SIZE_INVALID"})
            return
        body = handler.rfile.read(length)
        supplied_digest = handler.headers.get("Content-Digest", "")
        expected_digest = "sha-256=:" + base64.b64encode(
            hashlib.sha256(body).digest()
        ).decode("ascii") + ":"
        if not hmac.compare_digest(supplied_digest, expected_digest):
            self._reply(handler, 400, {"error": "PIC_TARGET_CONTENT_DIGEST_INVALID"})
            return
        try:
            request = json.loads(body)
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._reply(handler, 400, {"error": "PIC_TARGET_JSON_INVALID"})
            return
        scenario_document = json.loads(TARGET_SCENARIOS.read_text(encoding="utf-8"))
        protocol = scenario_document["executionProtocol"]
        cleanup_path = protocol["cleanupPath"]
        if handler.path == cleanup_path:
            self._cleanup(handler, request, len(scenario_document["requiredScenarios"]))
            return
        scenario_prefix, scenario_suffix = protocol["scenarioPathTemplate"].split(
            "{scenarioId}"
        )
        if not handler.path.startswith(scenario_prefix) or not handler.path.endswith(
            scenario_suffix
        ):
            self._reply(handler, 404, {"error": "PIC_TARGET_PATH_UNKNOWN"})
            return
        path_end = len(handler.path) - len(scenario_suffix) if scenario_suffix else None
        scenario_id = handler.path[len(scenario_prefix):path_end]
        required = scenario_document["requiredScenarios"]
        if self._expected_index >= len(required) or (
            required[self._expected_index]["id"] != scenario_id
        ):
            self._reply(handler, 409, {"error": "PIC_TARGET_SCENARIO_ORDER_INVALID"})
            return
        if (
            not isinstance(request, dict)
            or set(request) != {
                "requestVersion",
                "contractVersion",
                "scenarioSetVersion",
                "scenarioId",
                "syntheticInputDigest",
            }
            or request["requestVersion"] != "PIC-TARGET-REQUEST-1.0.0"
            or request["contractVersion"] != "PIC-1.0.0"
            or request["scenarioSetVersion"] != "PIC-TARGET-SCENARIOS-1.0.0"
            or request["scenarioId"] != scenario_id
            or not HEX64.fullmatch(str(request["syntheticInputDigest"]))
        ):
            self._reply(handler, 400, {"error": "PIC_TARGET_REQUEST_INVALID"})
            return
        request_digest = hashlib.sha256(body).hexdigest()
        if scenario_id == "transient-retry" and self.transient_failures_served == 0:
            self._transient_request_digest = request_digest
            self.transient_failures_served += 1
            self._reply(handler, 503, {"error": "PIC_TARGET_TRANSIENT"})
            return
        if scenario_id == "transient-retry" and not hmac.compare_digest(
            str(self._transient_request_digest), request_digest
        ):
            self._reply(handler, 409, {"error": "PIC_TARGET_RETRY_MUTATED"})
            return

        expected_effect = bool(required[self._expected_index]["sideEffectExpected"])
        external_one = local_runner.sha256_bytes(b"PIC-TARGET-EXTERNAL-ONE")
        external_two = local_runner.sha256_bytes(b"PIC-TARGET-EXTERNAL-TWO")
        result: dict[str, Any] = {
            "id": scenario_id,
            "status": "pass",
            "receiptOrChallengeDigest": local_runner.sha256_bytes(
                ("PIC-TARGET-RESULT-" + scenario_id).encode("ascii")
            ),
            "sideEffectExpected": expected_effect,
            "sideEffectCount": 1 if expected_effect else 0,
        }
        if expected_effect:
            result["externalReferenceDigest"] = (
                external_two if scenario_id == "revoke" else external_one
            )
            result["newObjectCount"] = 1 if scenario_id in {"create", "revoke"} else 0
        elif scenario_id == "duplicate-idempotency":
            result["originalExternalReferenceDigest"] = external_one
            result["newObjectCount"] = 0
        if scenario_id in {"create", "revoke"}:
            self._created += 1
        if scenario_id == "close":
            self._closed += 1
        if scenario_id == "revoke":
            self._revoked += 1
        self._expected_index += 1
        self._reply(
            handler,
            200,
            {"sandboxRunId": self.sandbox_run_id, "result": result},
        )

    def _cleanup(
        self,
        handler: BaseHTTPRequestHandler,
        request: Any,
        required_count: int,
    ) -> None:
        if (
            not isinstance(request, dict)
            or request
            != {
                "cleanupVersion": "PIC-SANDBOX-CLEANUP-1.0.0",
                "sandboxRunId": self.sandbox_run_id,
            }
            or self._expected_index != required_count
        ):
            self._reply(handler, 409, {"error": "PIC_TARGET_CLEANUP_INVALID"})
            return
        self._reply(
            handler,
            200,
            {
                "cleanupVersion": "PIC-SANDBOX-CLEANUP-1.0.0",
                "sandboxRunId": self.sandbox_run_id,
                "sandboxCleanupResult": "pass",
                "orphanCount": 0,
                "approvedRetainedCount": 0,
                "syntheticExternalObjectsCreated": self._created,
                "syntheticExternalObjectsClosed": self._closed,
                "syntheticExternalObjectsRevoked": self._revoked,
                "referenceRouteWatermarkFrom": 0,
                "referenceRouteWatermarkTo": 9,
                "finalExternalState": "revoked",
            },
        )


def _target_request(
    preflight: TargetPreflightResult,
    path: str,
    document: dict[str, Any],
) -> tuple[int, dict[str, Any]]:
    body = canonical_json_bytes(document)
    credentials = preflight.credentials
    if not isinstance(credentials, dict):
        raise ValueError("PIC_TARGET_CREDENTIAL_BUNDLE_INVALID")
    host, port, base_path = _validated_target_uri(
        preflight.handoff["targetDescriptor"]["baseUri"]
    )
    context = ssl.create_default_context(cafile=credentials["caFile"])
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(
        certfile=credentials["clientCertFile"], keyfile=credentials["clientKeyFile"]
    )
    connection = http.client.HTTPSConnection(host, port, context=context, timeout=15)
    headers = {
        "Accept": "application/json",
        "Authorization": "Bearer " + credentials["bearerToken"],
        "Content-Type": "application/json",
        "Content-Digest": "sha-256=:" + base64.b64encode(
            hashlib.sha256(body).digest()
        ).decode("ascii") + ":",
    }
    try:
        connection.connect()
        request_binding = _tls_peer_binding(connection.sock)
        expected_binding = preflight.network_binding
        if (
            expected_binding is None
            or request_binding != expected_binding
            or preflight.handoff["peerSpkiSha256"]
            != "sha256:" + request_binding["peerSpkiSha256"]
        ):
            raise RuntimeError("PIC_TARGET_REQUEST_PEER_BINDING_MISMATCH")
        connection.request("POST", base_path + path, body=body, headers=headers)
        response = connection.getresponse()
        response_body = response.read(65_537)
        status = response.status
        media_type = response.getheader("Content-Type", "").split(";", 1)[0]
    except (OSError, ssl.SSLError, http.client.HTTPException) as failure:
        raise RuntimeError("PIC_TARGET_REQUEST_FAILED") from failure
    finally:
        connection.close()
    if len(response_body) > 65_536 or media_type != "application/json":
        raise RuntimeError("PIC_TARGET_RESPONSE_INVALID")
    try:
        parsed = json.loads(response_body)
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise RuntimeError("PIC_TARGET_RESPONSE_JSON_INVALID") from failure
    if not isinstance(parsed, dict):
        raise RuntimeError("PIC_TARGET_RESPONSE_SHAPE_INVALID")
    return status, parsed


def run_target_scenarios(
    preflight: TargetPreflightResult,
    *,
    subject_commit: str,
    subject_tree: str,
    run_at: str,
) -> dict[str, Any]:
    if preflight.network_binding is None:
        raise RuntimeError("PIC_TARGET_NETWORK_BINDING_REQUIRED")
    if not HEX40.fullmatch(subject_commit) or not HEX40.fullmatch(subject_tree):
        raise ValueError("PIC_TARGET_SUBJECT_INVALID")
    scenarios = json.loads(TARGET_SCENARIOS.read_text(encoding="utf-8"))
    expected_protocol = {
        "version": "PIC-TARGET-EXECUTION-1.0.0",
        "method": "POST",
        "scenarioPathTemplate": "/_scholarsense/pic/conformance/v1/scenarios/{scenarioId}",
        "cleanupPath": "/_scholarsense/pic/conformance/v1/cleanup",
        "requestMediaType": "application/json",
        "responseMediaType": "application/json",
    }
    if scenarios.get("executionProtocol") != expected_protocol:
        raise RuntimeError("PIC_TARGET_EXECUTION_PROTOCOL_INVALID")
    results: list[dict[str, Any]] = []
    log_records: list[dict[str, Any]] = []
    sandbox_run_id: str | None = None
    for locked in scenarios["requiredScenarios"]:
        scenario_id = locked["id"]
        request = {
            "requestVersion": "PIC-TARGET-REQUEST-1.0.0",
            "contractVersion": scenarios["contractVersion"],
            "scenarioSetVersion": scenarios["version"],
            "scenarioId": scenario_id,
            "syntheticInputDigest": local_runner.sha256_bytes(
                ("PIC-TARGET-INPUT-" + scenario_id).encode("ascii")
            ),
        }
        path = expected_protocol["scenarioPathTemplate"].replace(
            "{scenarioId}", scenario_id
        )
        status, response = _target_request(preflight, path, request)
        if scenario_id == "transient-retry" and status == 503:
            log_records.append({"id": scenario_id, "httpStatus": status})
            status, response = _target_request(preflight, path, request)
        if status != 200 or set(response) != {"sandboxRunId", "result"}:
            raise RuntimeError(f"PIC_TARGET_SCENARIO_FAILED:{scenario_id}:{status}")
        current_run_id = response["sandboxRunId"]
        if not isinstance(current_run_id, str) or not current_run_id:
            raise RuntimeError("PIC_TARGET_SANDBOX_RUN_ID_INVALID")
        if sandbox_run_id is None:
            sandbox_run_id = current_run_id
        elif sandbox_run_id != current_run_id:
            raise RuntimeError("PIC_TARGET_SANDBOX_RUN_ID_CHANGED")
        result = response["result"]
        if (
            not isinstance(result, dict)
            or result.get("id") != scenario_id
            or result.get("status") != "pass"
            or result.get("sideEffectExpected") != locked["sideEffectExpected"]
            or result.get("sideEffectCount")
            != (1 if locked["sideEffectExpected"] else 0)
        ):
            raise RuntimeError(f"PIC_TARGET_SCENARIO_ASSERTION_FAILED:{scenario_id}")
        results.append(result)
        log_records.append({"id": scenario_id, "httpStatus": status, "result": result})
    if sandbox_run_id is None:
        raise RuntimeError("PIC_TARGET_SANDBOX_RUN_ID_MISSING")
    cleanup_status, cleanup = _target_request(
        preflight,
        expected_protocol["cleanupPath"],
        {
            "cleanupVersion": "PIC-SANDBOX-CLEANUP-1.0.0",
            "sandboxRunId": sandbox_run_id,
        },
    )
    expected_cleanup_fields = {
        "cleanupVersion",
        "sandboxRunId",
        "sandboxCleanupResult",
        "orphanCount",
        "approvedRetainedCount",
        "syntheticExternalObjectsCreated",
        "syntheticExternalObjectsClosed",
        "syntheticExternalObjectsRevoked",
        "referenceRouteWatermarkFrom",
        "referenceRouteWatermarkTo",
        "finalExternalState",
    }
    if (
        cleanup_status != 200
        or set(cleanup) != expected_cleanup_fields
        or cleanup["sandboxRunId"] != sandbox_run_id
        or cleanup["sandboxCleanupResult"] != "pass"
        or cleanup["orphanCount"] != 0
        or cleanup["approvedRetainedCount"] != 0
        or cleanup["syntheticExternalObjectsCreated"]
        != cleanup["syntheticExternalObjectsClosed"]
        + cleanup["syntheticExternalObjectsRevoked"]
    ):
        raise RuntimeError("PIC_TARGET_CLEANUP_FAILED")
    log_records.append({"cleanup": cleanup, "httpStatus": cleanup_status})
    handoff = preflight.handoff
    binding = preflight.network_binding
    evidence: dict[str, Any] = {
        "version": "PIC-EVIDENCE-1.0.0",
        "evidenceClass": "target-managed-non-production-sandbox",
        "subjectCommit": subject_commit,
        "subjectTree": subject_tree,
        "contractVersion": "PIC-1.0.0",
        "contractDigest": local_runner.file_sha256(local_runner.CONTRACT),
        "profileVersion": "PIC-RUNTIME-1.0.0",
        "profileDigest": local_runner.file_sha256(local_runner.PROFILE),
        "scenarioSetVersion": scenarios["version"],
        "scenarioSetDigest": local_runner.file_sha256(TARGET_SCENARIOS),
        "runAt": run_at,
        "command": "scripts/run_public_integration_target_sandbox_tests.py --evidence <candidate-tree-outside>",
        "commandExitCode": 0,
        "logDigest": local_runner.sha256_bytes(canonical_json_bytes(log_records)),
        "evidenceDigest": "0" * 64,
        "productionEligible": False,
        "productionRuntimeClaim": "none",
        "evidenceScope": "contract-reference-adapter-only",
        "productionDomainObjectsCreated": 0,
        "realBusinessObjectsCreated": 0,
        "productionConsumerInstalled": False,
        "productionConsumerWatermarkAdvanced": False,
        "publicTaskLifecycleClaim": "none",
        "conditionalSkipCount": 0,
        "targetSandboxConnected": True,
        "handoffId": handoff["handoffId"],
        "handoffRevision": handoff["revision"],
        "previousRevisionDigest": handoff["previousRevisionDigest"],
        "handoffBindingDigest": preflight.handoff_digest,
        "handoffExpectedDigest": preflight.handoff_digest,
        "handoffIssuedAt": handoff["issuedAt"],
        "handoffNotBefore": handoff["notBefore"],
        "handoffExpiresAt": handoff["expiresAt"],
        "signerKeyId": handoff["signerKeyId"],
        "trustRootDigest": preflight.trust_root_digest,
        "signatureVerified": preflight.signature_verified,
        "approvedAuthorityDigest": local_runner.sha256_bytes(
            handoff["authority"].encode("utf-8")
        ),
        "peerCertificateSpkiDigest": binding["peerSpkiSha256"],
        "peerCertificateChainDigest": binding["peerChainSha256"],
        "sandboxRunId": sandbox_run_id,
        "overallResult": "pass",
        "failedCount": 0,
        "skippedCount": 0,
        "scenarioResults": results,
        "sandboxCleanupResult": cleanup["sandboxCleanupResult"],
        "orphanCount": cleanup["orphanCount"],
        "approvedRetainedCount": cleanup["approvedRetainedCount"],
        "syntheticExternalObjectsCreated": cleanup["syntheticExternalObjectsCreated"],
        "syntheticExternalObjectsClosed": cleanup["syntheticExternalObjectsClosed"],
        "syntheticExternalObjectsRevoked": cleanup["syntheticExternalObjectsRevoked"],
        "referenceRouteWatermarkFrom": cleanup["referenceRouteWatermarkFrom"],
        "referenceRouteWatermarkTo": cleanup["referenceRouteWatermarkTo"],
        "finalExternalState": cleanup["finalExternalState"],
    }
    evidence["evidenceDigest"] = local_runner.calculate_evidence_digest(evidence)
    issues = local_runner.validate_evidence(evidence)
    if issues:
        raise RuntimeError(";".join(issues))
    return evidence


def synthetic_handoff_fixture(trust_anchor_digest: str) -> dict[str, Any]:
    return {
        "handoffVersion": "PIC-TARGET-HANDOFF-1.0.0",
        "handoffId": "019fc688-380d-7391-b3d0-877e0f9b3026",
        "revision": 1,
        "previousRevisionDigest": None,
        "issuedAt": "2026-08-03T07:55:00Z",
        "notBefore": "2026-08-03T08:00:00Z",
        "expiresAt": "2026-08-04T08:00:00Z",
        "signerKeyId": "pic-target-authority-01",
        "authority": "synthetic-school-target-authority",
        "environment": "non-production-sandbox",
        "trustAnchorDigest": trust_anchor_digest,
        "peerSpkiSha256": "sha256:" + "1" * 64,
        "capabilities": CAPABILITIES,
        "protocols": {
            "receipt": "PIC-RECEIPT-1.0.0",
            "challenge": "PIC-CHALLENGE-1.0.0",
            "sandboxCleanup": "PIC-SANDBOX-CLEANUP-1.0.0",
            "terminalByStableWorkItemKey": True,
            "rejectStaleSourceVersion": True,
        },
        "credentialRef": "credential-ref:env/SCHOLARSENSE_PIC_SYNTHETIC_CREDENTIAL",
        "targetDescriptor": {
            "descriptorVersion": "PIC-TARGET-DESCRIPTOR-1.0.0",
            "baseUri": "https://sandbox.invalid/pic",
        },
    }


def synthetic_signature_fixture(digest: str, signature: bytes) -> dict[str, Any]:
    return {
        "signatureVersion": "PIC-TARGET-HANDOFF-SIGNATURE-1.0.0",
        "algorithm": "Ed25519",
        "keyId": "pic-target-authority-01",
        "canonicalizationProfile": "SCHOLARSENSE-CANONICAL-JSON-1.0.0",
        "handoffSha256": digest,
        "signatureBase64": base64.b64encode(signature).decode("ascii"),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--subject-commit")
    parser.add_argument("--subject-tree")
    parser.add_argument(
        "--trusted-now",
        default=dt.datetime.now(dt.UTC).replace(microsecond=0).isoformat().replace(
            "+00:00", "Z"
        ),
    )
    args = parser.parse_args(argv)
    output = Path(args.evidence).resolve()
    if output == ROOT or ROOT in output.parents:
        raise RuntimeError("PIC_TARGET_EVIDENCE_MUST_BE_CANDIDATE_TREE_OUTSIDE")
    if subprocess.check_output(
        ["git", "status", "--porcelain"], cwd=ROOT, text=True
    ).strip():
        raise RuntimeError("PIC_TARGET_EVIDENCE_REQUIRES_TRACKED_CLEAN_CANDIDATE")
    subject_commit, subject_tree = local_runner.resolve_git_subject(
        args.subject_commit, args.subject_tree
    )
    run_at = args.trusted_now
    preflight = preflight_target_handoff(dict(os.environ), now=run_at)
    evidence = run_target_scenarios(
        preflight,
        subject_commit=subject_commit,
        subject_tree=subject_tree,
        run_at=run_at,
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(canonical_json_bytes(evidence) + b"\n")
    print(
        "public-integration-target: PASS "
        "(scenarios=10; failed=0; skipped=0; cleanup=pass; productionEligible=false)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
