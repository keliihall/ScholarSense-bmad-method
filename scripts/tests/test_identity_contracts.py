from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_identity_contracts import validate  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class IdentityContractsTest(unittest.TestCase):
    def test_controlled_host_and_identity_contracts_pass(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))

    def test_host_contract_rejects_unknown_fields_events_and_sensitive_payloads(self) -> None:
        cases = {
            "unknown field": ("HOST_MESSAGE_SCHEMA_REJECTED", lambda value: value.update({"extra": True})),
            "unknown event": ("HOST_EVENT_TYPE_INVALID", lambda value: value.update({"eventType": "token.shared"})),
            "sensitive payload": (
                "HOST_PAYLOAD_SENSITIVE_FIELD_FORBIDDEN",
                lambda value: value["payload"].update({"accessToken": "forbidden"}),
            ),
        }
        for label, (reason, mutate) in cases.items():
            with self.subTest(label=label), self.fixture() as root:
                path = root / "contracts/host/examples/valid/host-ready.json"
                value = json.loads(path.read_text(encoding="utf-8"))
                mutate(value)
                path.write_text(json.dumps(value), encoding="utf-8")
                self.assert_reason(root, reason)

    def test_host_challenge_contract_requires_the_frozen_bootstrap_proof(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/host/examples/valid/host-challenge.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["payload"].pop("origin")
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "HOST_CHALLENGE_PAYLOAD_INVALID")

    def test_continuation_contract_rejects_open_redirects_and_unknown_business_targets(self) -> None:
        cases = {
            "absolute": "https://attacker.example/steal",
            "protocol relative": "//attacker.example/steal",
            "business target": "work-item.unknown",
        }
        for label, target in cases.items():
            with self.subTest(label=label), self.fixture() as root:
                path = root / "contracts/identity/examples/valid/reauthentication-request.json"
                value = json.loads(path.read_text(encoding="utf-8"))
                value["targetRouteId"] = target
                path.write_text(json.dumps(value), encoding="utf-8")
                self.assert_reason(root, "CONTINUATION_TARGET_FORBIDDEN")

    def test_negative_fixture_catalog_covers_every_frozen_rejection(self) -> None:
        catalog = json.loads(
            (PROJECT_ROOT / "contracts/identity/examples/negative-catalog.json").read_text(encoding="utf-8")
        )
        self.assertEqual(
            {
                "host-origin-forbidden",
                "host-source-forbidden",
                "host-message-unknown-field",
                "host-event-unknown",
                "host-message-expired",
                "host-nonce-replayed",
                "host-message-replayed",
                "host-bootstrap-already-used",
                "continuation-open-redirect",
                "continuation-expired",
                "csrf-invalid",
                "identity-session-version-conflict",
                "idempotency-key-mismatch",
            },
            {entry["id"] for entry in catalog["cases"]},
        )

    def test_contract_lock_detects_silent_schema_drift(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/host/host-envelope.schema.json"
            path.write_text(path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
            self.assert_reason(root, "IDENTITY_CONTRACT_LOCK_DIGEST_MISMATCH")

    def test_evidence_verified_clock_requires_a_matching_signed_observation(self) -> None:
        with self.fixture() as root:
            path = root / "contracts/identity/identity-runtime-profile-1.0.0.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["runtimeBindings"]["clockSource"] = "config://stage/campus-ntp-a"
            value["missingRequirements"].remove("clockSource")
            value["clockBindingStatus"] = "evidence-verified"
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "IDENTITY_CLOCK_EVIDENCE_INVALID")

            value["evidence"]["clockSynchronization"] = \
                "evidence://signed/clock/other-ntp.json"
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "IDENTITY_CLOCK_EVIDENCE_INVALID")

            value["evidence"]["clockSynchronization"] = \
                "evidence://signed/clock/campus-ntp-a.json"
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assert_reason(root, "IDENTITY_CLOCK_EVIDENCE_INVALID")

    def test_evidence_verified_clock_parses_the_observation_and_verifies_its_bundle(self) -> None:
        with self.fixture() as root:
            profile_path = root / "contracts/identity/identity-runtime-profile-1.0.0.json"
            profile = json.loads(profile_path.read_text(encoding="utf-8"))
            profile["runtimeBindings"]["clockSource"] = "config://stage/campus-ntp-a"
            profile["missingRequirements"].remove("clockSource")
            profile["clockBindingStatus"] = "evidence-verified"
            profile["evidence"]["clockSynchronization"] = \
                "evidence://signed/clock/campus-ntp-a.json"
            profile_path.write_text(json.dumps(profile), encoding="utf-8")
            evidence_path = root / "evidence/signed/clock/campus-ntp-a.json"
            evidence_path.parent.mkdir(parents=True)
            evidence_path.write_text(json.dumps({
                "version": "CLOCK-SYNCHRONIZATION-EVIDENCE-1.0.0",
                "status": "passed",
                "sourceId": "campus-ntp-a",
                "clockBindingVersion": "AUDIT-CLOCK-BINDING-1.0.0",
                "sourceCommit": "a0c8a9cba10d963c41623d27a8480dbbbddea393",
                "offsetMs": 12,
                "observedAt": "2026-07-20T01:59:30Z",
                "freshUntil": "2026-07-20T02:00:30Z",
            }), encoding="utf-8")
            evidence_path.with_suffix(".json.sigstore.json").write_text("{}", encoding="utf-8")

            with mock.patch("check_identity_contracts._clock_signature_verified", return_value=True):
                issues = validate(root)
            self.assertFalse(any(
                issue.startswith("IDENTITY_CLOCK_EVIDENCE_INVALID") for issue in issues), issues)

            evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
            evidence["offsetMs"] = 101
            evidence_path.write_text(json.dumps(evidence), encoding="utf-8")
            with mock.patch("check_identity_contracts._clock_signature_verified", return_value=True):
                self.assert_reason(root, "IDENTITY_CLOCK_EVIDENCE_INVALID")

    def assert_reason(self, root: Path, reason: str) -> None:
        actual = validate(root)
        self.assertTrue(any(value.startswith(reason) for value in actual), f"expected {reason}, got {actual}")

    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        shutil.copytree(PROJECT_ROOT / "contracts/host", root / "contracts/host")
        shutil.copytree(PROJECT_ROOT / "contracts/identity", root / "contracts/identity")

        class Context:
            def __enter__(self):
                return root

            def __exit__(self, *_args):
                temporary.cleanup()

        return Context()


if __name__ == "__main__":
    unittest.main()
