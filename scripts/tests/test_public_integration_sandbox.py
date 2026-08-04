import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

from scripts import run_public_integration_sandbox_tests as local_runner
from scripts import run_public_integration_target_sandbox_tests as target_runner


ROOT = Path(__file__).resolve().parents[2]


def _gate_results(**exit_codes: int):
    return {
        gate_id: local_runner.ExecutableGateResult(
            gate_id=gate_id,
            command=("synthetic-executable-gate", gate_id),
            exit_code=exit_codes.get(gate_id, 0),
            output=("observed:" + gate_id).encode(),
        )
        for gate_id in local_runner.REQUIRED_GATE_IDS
    }


class PublicIntegrationLocalSandboxTests(unittest.TestCase):
    def test_failed_executable_gate_is_not_reported_as_pass(self):
        result = local_runner.run_local_scenarios(gate_results=_gate_results(
            **{"reference-adapters": 1}
        ))
        self.assertGreater(result.failed_count, 0)
        self.assertEqual("fail", next(
            item["status"] for item in result.scenario_results
            if item["id"] == "create"
        ))
        evidence = local_runner.build_local_evidence(
            subject_commit="a" * 40,
            subject_tree="b" * 40,
            run_at="2026-08-03T08:00:00Z",
            command="synthetic-failed-local-test",
            log_bytes=b"failed gate",
            result=result,
        )
        self.assertEqual("fail", evidence["overallResult"])
        self.assertEqual([], local_runner.validate_evidence(evidence))

    def test_skip_executable_gates_cannot_emit_pass_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "evidence.json"
            with self.assertRaisesRegex(
                RuntimeError, "PIC_LOCAL_EXECUTABLE_GATES_REQUIRED"
            ):
                local_runner.main([
                    "--evidence", str(output),
                    "--development-allow-dirty",
                    "--skip-executable-gates",
                ])
            self.assertFalse(output.exists())

    def test_dirty_development_mode_cannot_emit_committed_candidate_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "evidence.json"
            with self.assertRaisesRegex(
                RuntimeError, "PIC_LOCAL_EVIDENCE_REQUIRES_TRACKED_CLEAN_CANDIDATE"
            ):
                local_runner.main([
                    "--evidence", str(output),
                    "--development-allow-dirty",
                ])
            self.assertFalse(output.exists())

    def test_subject_overrides_must_match_the_executed_git_candidate(self):
        with mock.patch.object(
            local_runner,
            "_git_value",
            side_effect=("c" * 40, "d" * 40),
        ):
            with self.assertRaisesRegex(ValueError, "PIC_EVIDENCE_SUBJECT_OVERRIDE_MISMATCH"):
                local_runner.resolve_git_subject("a" * 40, "b" * 40)

    def test_local_model_covers_locked_scenarios_and_invariants(self):
        result = local_runner.run_local_scenarios(gate_results=_gate_results())
        locked = json.loads(
            (ROOT / "contracts/public-integration/"
             "public-integration-target-scenarios-1.0.0.json").read_text()
        )
        self.assertEqual(
            [item["id"] for item in locked["requiredScenarios"]],
            [item["id"] for item in result.scenario_results],
        )
        self.assertEqual(0, result.failed_count)
        self.assertEqual(0, result.skipped_count)
        self.assertEqual(2, result.synthetic_created)
        self.assertEqual(
            result.synthetic_created,
            result.synthetic_closed + result.synthetic_revoked,
        )
        self.assertEqual("revoked", result.final_external_state)
        self.assertEqual(9, result.reference_route_watermark_to)
        self.assertTrue(result.internal_invariants["sameExternalTaskReference"])
        self.assertTrue(result.internal_invariants["twoIntentMappingsNoThirdOnReplay"])
        self.assertTrue(result.internal_invariants["routeGapRecoveredInOrder"])
        self.assertTrue(result.internal_invariants["terminalLateConfirmFenced"])
        self.assertTrue(result.internal_invariants["sameMajorCutoverNoLoss"])
        self.assertTrue(result.internal_invariants["crossMajorZeroProviderCalls"])
        self.assertTrue(result.internal_invariants["sloBoundaryExact"])

    def test_local_evidence_is_closed_privacy_safe_and_digest_stable(self):
        result = local_runner.run_local_scenarios(gate_results=_gate_results())
        evidence = local_runner.build_local_evidence(
            subject_commit="a" * 40,
            subject_tree="b" * 40,
            run_at="2026-08-03T08:00:00Z",
            command="synthetic-local-test",
            log_bytes=b"deterministic local log",
            result=result,
        )
        self.assertEqual([], local_runner.validate_evidence(evidence))
        self.assertFalse(evidence["targetSandboxConnected"])
        self.assertFalse(evidence["productionEligible"])
        self.assertEqual(0, evidence["realBusinessObjectsCreated"])
        serialized = json.dumps(evidence, sort_keys=True)
        for canary in ("student-name", "external-task-sensitive-ref", "secret-token"):
            self.assertNotIn(canary, serialized)
        self.assertEqual(
            evidence["evidenceDigest"],
            local_runner.calculate_evidence_digest(evidence),
        )


class PublicIntegrationTargetPreflightTests(unittest.TestCase):
    def test_digest_mismatch_stops_before_signature_network_or_credentials(self):
        calls = {"signature": 0, "network": 0, "credential": 0}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            handoff = root / "handoff.json"
            signature = root / "signature.json"
            trust = root / "trust.pem"
            handoff.write_text("{}")
            signature.write_text("{}")
            trust.write_text("not-a-key")
            env = {
                "SCHOLARSENSE_PIC_TARGET_HANDOFF_FILE": str(handoff),
                "SCHOLARSENSE_PIC_TARGET_HANDOFF_SIGNATURE_FILE": str(signature),
                "SCHOLARSENSE_PIC_TARGET_HANDOFF_TRUST_ROOT_FILE": str(trust),
                "SCHOLARSENSE_PIC_TARGET_HANDOFF_EXPECTED_SHA256": "0" * 64,
            }
            with self.assertRaisesRegex(ValueError, "EXPECTED_DIGEST_MISMATCH"):
                target_runner.preflight_target_handoff(
                    env,
                    now="2026-08-03T08:00:00Z",
                    signature_verifier=lambda *_: calls.__setitem__(
                        "signature", calls["signature"] + 1
                    ) or True,
                    network_probe=lambda *_: calls.__setitem__(
                        "network", calls["network"] + 1
                    ),
                    credential_resolver=lambda *_: calls.__setitem__(
                        "credential", calls["credential"] + 1
                    ),
                )
        self.assertEqual({"signature": 0, "network": 0, "credential": 0}, calls)

    def test_valid_signed_handoff_reaches_resolution_only_after_preflight(self):
        if subprocess.run(
            ["openssl", "version"], capture_output=True, check=False
        ).returncode != 0:
            self.skipTest("openssl unavailable")
        calls = []
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            private_key = root / "private.pem"
            public_key = root / "public.pem"
            subprocess.run(
                ["openssl", "genpkey", "-algorithm", "ED25519", "-out", private_key],
                check=True,
                capture_output=True,
            )
            subprocess.run(
                ["openssl", "pkey", "-in", private_key, "-pubout", "-out", public_key],
                check=True,
                capture_output=True,
            )
            trust_digest = "sha256:" + hashlib.sha256(public_key.read_bytes()).hexdigest()
            handoff_document = target_runner.synthetic_handoff_fixture(trust_digest)
            handoff_bytes = local_runner.canonical_json_bytes(handoff_document)
            handoff = root / "handoff.json"
            handoff.write_bytes(handoff_bytes)
            raw_signature = root / "signature.raw"
            subprocess.run(
                ["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", private_key,
                 "-in", handoff, "-out", raw_signature],
                check=True,
                capture_output=True,
            )
            signature = root / "signature.json"
            signature.write_text(json.dumps(target_runner.synthetic_signature_fixture(
                hashlib.sha256(handoff_bytes).hexdigest(),
                raw_signature.read_bytes(),
            )))
            env = {
                "SCHOLARSENSE_PIC_TARGET_HANDOFF_FILE": str(handoff),
                "SCHOLARSENSE_PIC_TARGET_HANDOFF_SIGNATURE_FILE": str(signature),
                "SCHOLARSENSE_PIC_TARGET_HANDOFF_TRUST_ROOT_FILE": str(public_key),
                "SCHOLARSENSE_PIC_TARGET_HANDOFF_EXPECTED_SHA256": hashlib.sha256(
                    handoff_bytes
                ).hexdigest(),
            }
            result = target_runner.preflight_target_handoff(
                env,
                now="2026-08-03T08:00:00Z",
                network_probe=lambda *_: calls.append("network"),
                credential_resolver=lambda ref: calls.append("credential") or {"ref": ref},
            )
        self.assertTrue(result.signature_verified)
        self.assertEqual(1, result.handoff["revision"])
        self.assertEqual(["credential", "network"], calls)

    def test_tamper_old_revision_time_and_wrong_root_fail_before_credentials_or_network(self):
        cases = (
            ("tampered", "2026-08-03T08:00:00Z", "EXPECTED_DIGEST_MISMATCH"),
            ("old-revision", "2026-08-03T08:00:00Z", "EXPECTED_DIGEST_MISMATCH"),
            ("not-yet-valid", "2026-08-03T07:59:59Z", "NOT_CURRENT"),
            ("expired", "2026-08-04T08:00:01Z", "NOT_CURRENT"),
            ("validity-too-long", "2026-08-03T08:00:00Z", "VALIDITY_TOO_LONG"),
            ("wrong-root", "2026-08-03T08:00:00Z", "TRUST_ROOT_DIGEST_MISMATCH"),
        )
        for case, trusted_now, expected_error in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as directory:
                calls = {"credential": 0, "network": 0}
                root = Path(directory)
                trust = root / "trust.pem"
                trust.write_bytes(b"synthetic-trust-root")
                trust_digest = "sha256:" + hashlib.sha256(
                    trust.read_bytes()
                ).hexdigest()
                document = target_runner.synthetic_handoff_fixture(trust_digest)
                if case == "validity-too-long":
                    document["expiresAt"] = "2026-08-04T08:00:01Z"
                if case == "wrong-root":
                    document["trustAnchorDigest"] = "sha256:" + "9" * 64
                canonical = local_runner.canonical_json_bytes(document)
                expected_digest = hashlib.sha256(canonical).hexdigest()
                if case == "tampered":
                    document["authority"] = "tampered-authority"
                    canonical = local_runner.canonical_json_bytes(document)
                if case == "old-revision":
                    expected_digest = "8" * 64
                handoff = root / "handoff.json"
                handoff.write_bytes(canonical)
                signature = root / "signature.json"
                signature.write_text(json.dumps(
                    target_runner.synthetic_signature_fixture(
                        hashlib.sha256(canonical).hexdigest(), b"0" * 64
                    )
                ))
                environment = {
                    "SCHOLARSENSE_PIC_TARGET_HANDOFF_FILE": str(handoff),
                    "SCHOLARSENSE_PIC_TARGET_HANDOFF_SIGNATURE_FILE": str(signature),
                    "SCHOLARSENSE_PIC_TARGET_HANDOFF_TRUST_ROOT_FILE": str(trust),
                    "SCHOLARSENSE_PIC_TARGET_HANDOFF_EXPECTED_SHA256": expected_digest,
                }
                with self.assertRaisesRegex(ValueError, expected_error):
                    target_runner.preflight_target_handoff(
                        environment,
                        now=trusted_now,
                        signature_verifier=lambda *_: True,
                        credential_resolver=lambda *_: calls.__setitem__(
                            "credential", calls["credential"] + 1
                        ),
                        network_probe=lambda *_: calls.__setitem__(
                            "network", calls["network"] + 1
                        ),
                    )
                self.assertEqual({"credential": 0, "network": 0}, calls)


if __name__ == "__main__":
    unittest.main()
