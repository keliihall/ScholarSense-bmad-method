import json
import shutil
import tempfile
import unittest
import re
from pathlib import Path

from scripts import check_ingestion_quality_recovery_contracts as checker


ROOT = Path(__file__).resolve().parents[2]


class IngestionQualityRecoveryContractTest(unittest.TestCase):
    def test_repository_contract_is_valid(self) -> None:
        self.assertEqual([], checker.check(ROOT))

    def test_qrp_boundaries_are_machine_locked(self) -> None:
        with self.copy() as root:
            path = root / checker.POLICY
            policy = self.load(path)
            policy["sourceClasses"]["streaming"]["consecutivePassedBatches"] = 2
            policy["sampling"]["minimumSubjectWindows"] = 99
            path.write_text(json.dumps(policy), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "streaming 3 batches / 60 minutes")
            self.assertIssue(issues, "sample all-if-fewer / minimum 100 / mismatch zero")

    def test_business_sequence_cannot_be_replaced_by_transport_order(self) -> None:
        with self.copy() as root:
            path = root / checker.POLICY
            policy = self.load(path)
            policy["batchQualification"]["sequenceKey"] = ["occurredAt"]
            path.write_text(json.dumps(policy), encoding="utf-8")
            self.assertIssue(checker.check(root), "business batch sequence")

    def test_two_phase_recovery_cannot_auto_promote_eligible(self) -> None:
        with self.copy() as root:
            path = root / checker.ARCHITECTURE_CONTRACT
            contract = self.load(path)
            contract["twoPhaseRecovery"]["story25bTargetState"] = "eligible"
            path.write_text(json.dumps(contract), encoding="utf-8")
            self.assertIssue(checker.check(root), "2.5b recovering / 2.5c eligible")

    def test_conflict_matrix_selects_the_same_cross_owner_lease_protocol(self) -> None:
        with self.copy() as root:
            path = root / checker.DERIVATION
            matrix = self.load(path)
            matrix["conflictResolutions"][-1]["resolution"] = (
                "use one shared PostgreSQL transaction")
            path.write_text(json.dumps(matrix), encoding="utf-8")
            self.assertIssue(checker.check(root), "derivation matrix durable lease protocol")

    def test_hrap_owner_and_cross_owner_lease_protocol_are_unique(self) -> None:
        with self.copy() as root:
            path = root / checker.ARCHITECTURE_CONTRACT
            contract = self.load(path)
            contract["ownership"]["approvalReceiptTokenOwner"] = "ingestion-quality"
            contract["atomicExecution"]["mode"] = "best-effort-compensation"
            path.write_text(json.dumps(contract), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "identity-access HRAP owner")
            self.assertIssue(issues, "durable cross-owner execution authorization lease")

    def test_runtime_checker_binding_is_authoritative_and_unambiguous(self) -> None:
        with self.copy() as root:
            path = root / checker.CHECKER_BINDING
            binding = self.load(path)
            binding["resolution"]["multipleActiveNaturalPersonsForKey"] = (
                "pick-lexicographically-first")
            binding["quorum"] = "any-one"
            path.write_text(json.dumps(binding), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "ambiguous checker binding fail closed")
            self.assertIssue(issues, "all distinct business-owner bindings quorum")

    def test_hrap_request_receipt_token_and_lease_shapes_are_closed(self) -> None:
        with self.copy() as root:
            token_path = root / checker.HRAP_TOKEN_SCHEMA
            token = self.load(token_path)
            token["additionalProperties"] = True
            lease_path = root / checker.HRAP_LEASE_SCHEMA
            lease = self.load(lease_path)
            lease["properties"]["state"]["enum"] = ["reserved", "executed"]
            token_path.write_text(json.dumps(token), encoding="utf-8")
            lease_path.write_text(json.dumps(lease), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "closed HRAP request/receipt/token/lease schemas")
            self.assertIssue(issues, "lease terminal state matrix")

    def test_execution_lease_expiry_and_late_confirmation_are_locked(self) -> None:
        with self.copy() as root:
            path = root / checker.HRAP_RUNTIME_POLICY
            policy = self.load(path)
            policy["executionAuthorization"]["commitBoundary"] = (
                "committedAt-less-than-or-equal-authorizedUntil")
            policy["executionAuthorization"]["lateConfirmation"] = "reject-after-expiry"
            path.write_text(json.dumps(policy), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "half-open authorized execution boundary")
            self.assertIssue(issues, "late confirmation finalizes pre-expiry commit")

    def test_retention_keeps_each_owner_responsible_for_its_facts(self) -> None:
        with self.copy() as root:
            path = root / checker.ARCHITECTURE_CONTRACT
            contract = self.load(path)
            contract["retention"]["identityAccess"]["cleanupOwner"] = (
                "ingestion-quality-retention-workload")
            path.write_text(json.dumps(contract), encoding="utf-8")
            self.assertIssue(checker.check(root), "owner-local HRAP and recovery retention")

    def test_source_classes_have_explicit_successor_approval_evidence(self) -> None:
        with self.copy() as root:
            path = root / checker.SOURCE_REGISTRY
            registry = self.load(path)
            registry["sources"][0].pop("approvalRef")
            registry["approval"]["approvedBindingSetDigest"] = "sha256:" + "0" * 64
            path.write_text(json.dumps(registry), encoding="utf-8")
            self.assertIssue(checker.check(root), "source class approval/effective evidence")

    def test_sample_provider_public_api_is_exact_bounded_and_pii_free(self) -> None:
        with self.copy() as root:
            path = root / checker.SAMPLE_PROVIDER
            contract = self.load(path)
            contract["publicPort"] = (
                "ingestionquality/application/RecoverySampleRecomputePort")
            contract["resultFields"].append("studentRef")
            contract["bounds"]["timeoutMillis"] = 0
            path.write_text(json.dumps(contract), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "signal-evaluation public sample provider API")
            self.assertIssue(issues, "sample provider result PII allowlist")
            self.assertIssue(issues, "bounded sample provider timeout")

    def test_sample_provider_successor_exposes_bounded_self_contained_strata(self) -> None:
        with self.copy() as root:
            path = root / checker.SAMPLE_PROVIDER_SUCCESSOR
            contract = self.load(path)
            contract["resultFields"].remove("strata")
            contract["stratumFields"].append("studentRef")
            contract["strataRules"]["totals"] = "not-checked"
            path.write_text(json.dumps(contract), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "sample provider successor strata allowlist")
            self.assertIssue(issues, "sample provider successor strata totals")

    def test_sample_provider_predecessor_bytes_remain_locked(self) -> None:
        with self.copy() as root:
            path = root / checker.SAMPLE_PROVIDER
            contract = self.load(path)
            contract["resultFields"].append("strata")
            path.write_text(json.dumps(contract), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "sample provider predecessor digest")
            self.assertIssue(issues, "quality recovery contract lock mismatch")

    def test_runtime_successors_are_closed_self_contained_and_multi_rule(self) -> None:
        with self.copy() as root:
            result_path = root / checker.RECOVERY_RESULT_SUCCESSOR
            result = self.load(result_path)
            result["$defs"]["readinessEvidence"]["required"].remove(
                "requiredMembersEligible")
            evidence_path = root / checker.RECOVERY_EVIDENCE_SUCCESSOR
            evidence = self.load(evidence_path)
            evidence["$defs"]["recoveryTaskBinding"]["required"].remove(
                "eligibilityBindings")
            result_path.write_text(json.dumps(result), encoding="utf-8")
            evidence_path.write_text(json.dumps(evidence), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "validation result executable readiness successor")
            self.assertIssue(issues, "multi-RuleVersion self-contained evidence successor")

    def test_action_capability_is_literal_and_separate_from_page_entry(self) -> None:
        with self.copy() as root:
            schema_path = root / checker.AUTHORIZED_SHELL_SUCCESSOR
            schema = self.load(schema_path)
            schema["$defs"]["actionCapability"]["properties"]["actionType"] = {
                "type": "string"}
            contract_path = root / checker.ACTION_CAPABILITY
            contract = self.load(contract_path)
            contract["pageEntryCapability"] = "quality-fuse.recover"
            contract["actionRevocationEffect"] = "clear-page-read-caches"
            schema_path.write_text(json.dumps(schema), encoding="utf-8")
            contract_path.write_text(json.dumps(contract), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "literal quality-fuse.recover action capability")
            self.assertIssue(issues, "action capability separate from quality page entry")
            self.assertIssue(issues, "action revoke preserves quality read caches")

    def test_recovery_wire_contracts_are_closed_recovering_only_and_pii_free(self) -> None:
        with self.copy() as root:
            event_path = root / checker.RECOVERY_EVENT_SCHEMA
            event = self.load(event_path)
            event["properties"]["targetState"] = {"enum": ["recovering", "eligible"]}
            projection_path = root / checker.RECOVERY_PROJECTION_SCHEMA
            projection = self.load(projection_path)
            projection["properties"]["studentRef"] = {"type": "string"}
            retention_path = root / checker.RECOVERY_RETENTION
            retention = self.load(retention_path)
            retention["owners"]["identityAccess"]["cleanupOwner"] = (
                "ingestion-quality-retention-workload")
            event_path.write_text(json.dumps(event), encoding="utf-8")
            projection_path.write_text(json.dumps(projection), encoding="utf-8")
            retention_path.write_text(json.dumps(retention), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "recovery event fused to recovering only")
            self.assertIssue(issues, "recovery projection PII allowlist")
            self.assertIssue(issues, "owner-local recovery wire retention")

    def test_recovery_error_profile_never_reflects_external_or_free_text(self) -> None:
        with self.copy() as root:
            path = root / checker.RECOVERY_ERROR_PROFILE
            profile = self.load(path)
            profile["externalBodyHandling"] = "reflect"
            profile["responseFields"].append("reasonText")
            path.write_text(json.dumps(profile), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "recovery error privacy profile")

    def test_hrap_vectors_and_inner_raw_byte_lock_are_complete(self) -> None:
        with self.copy() as root:
            vectors_path = root / checker.HRAP_LIFECYCLE_VECTORS
            vectors = self.load(vectors_path)
            vectors["cases"] = vectors["cases"][:2]
            vectors_path.write_text(json.dumps(vectors), encoding="utf-8")
            lock_path = root / checker.HRAP_LOCK
            lock = self.load(lock_path)
            lock["files"].pop(next(iter(lock["files"])))
            lock_path.write_text(json.dumps(lock), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "HRAP lifecycle boundary and race vectors")
            self.assertIssue(issues, "high-risk authorization raw-byte lock")

    def test_hrap_valid_fixtures_match_closed_schema_shapes_offline(self) -> None:
        pairs = [
            (checker.HRAP_REQUEST_SCHEMA, checker.HRAP_REQUEST_FIXTURE),
            (checker.HRAP_RECEIPT_SCHEMA, checker.HRAP_RECEIPT_FIXTURE),
            (checker.HRAP_TOKEN_SCHEMA, checker.HRAP_TOKEN_FIXTURE),
            (checker.HRAP_LEASE_SCHEMA, checker.HRAP_LEASE_FIXTURE),
        ]
        for schema_path, fixture_path in pairs:
            schema = self.load(ROOT / schema_path)
            fixture = self.load(ROOT / fixture_path)
            self.assertEqual(set(schema["required"]), set(fixture))
            self.assertEqual(set(schema["properties"]), set(fixture))
            for field, rule in schema["properties"].items():
                value = fixture[field]
                if "const" in rule:
                    self.assertEqual(rule["const"], value, field)
                if "enum" in rule:
                    self.assertIn(value, rule["enum"], field)
                if "pattern" in rule and isinstance(value, str):
                    self.assertRegex(value, re.compile(rule["pattern"]), field)

    def test_final_predecessor_overlay_has_exact_path_digest_manifest(self) -> None:
        with self.copy() as root:
            path = root / checker.BASELINE_OVERLAY
            manifest = self.load(path)
            manifest["files"] = manifest["files"][:2]
            path.write_text(json.dumps(manifest), encoding="utf-8")
            self.assertIssue(checker.check(root), "complete Story 2.4/2.5a overlay manifest")

    def test_sample_provider_is_real_pii_free_and_fail_closed(self) -> None:
        with self.copy() as root:
            path = root / checker.ARCHITECTURE_CONTRACT
            contract = self.load(path)
            contract["sampleRecompute"]["providerNotInstalled"] = "mismatch-zero"
            contract["sampleRecompute"]["resultFields"].append("studentRef")
            path.write_text(json.dumps(contract), encoding="utf-8")
            issues = checker.check(root)
            self.assertIssue(issues, "provider-not-installed fail closed")
            self.assertIssue(issues, "sample summary PII allowlist")

    def test_preview_is_evidence_not_permission(self) -> None:
        with self.copy() as root:
            path = root / checker.ARCHITECTURE_CONTRACT
            contract = self.load(path)
            contract["impactPreview"]["authorizesExecution"] = True
            path.write_text(json.dumps(contract), encoding="utf-8")
            self.assertIssue(checker.check(root), "preview never authorizes execution")

    def test_command_successor_preserves_predecessor_bytes(self) -> None:
        with self.copy() as root:
            predecessor = root / checker.COMMAND_PREDECESSOR
            document = self.load(predecessor)
            document["runtimeEvidenceClaim"] = "mutated"
            predecessor.write_text(json.dumps(document), encoding="utf-8")
            self.assertIssue(checker.check(root), "recovery command predecessor digest")

    def test_unknown_policy_digest_provider_and_action_fail_closed(self) -> None:
        with self.copy() as root:
            path = root / checker.NEGATIVE
            fixture = self.load(path)
            fixture["cases"] = [
                case for case in fixture["cases"] if case["caseId"] != "unknown-action-type"
            ]
            path.write_text(json.dumps(fixture), encoding="utf-8")
            self.assertIssue(checker.check(root), "negative fixture matrix")

    def test_contract_lock_detects_mutation(self) -> None:
        with self.copy() as root:
            path = root / checker.VALID
            fixture = self.load(path)
            fixture["cases"][0]["expected"]["qualified"] = False
            path.write_text(json.dumps(fixture), encoding="utf-8")
            self.assertIssue(checker.check(root), "quality recovery contract lock mismatch")

    @staticmethod
    def assertIssue(issues: list[str], expected: str) -> None:
        if not any(expected in issue for issue in issues):
            raise AssertionError(f"missing issue containing {expected!r}: {issues}")

    @staticmethod
    def load(path: Path):
        return json.loads(path.read_text(encoding="utf-8"))

    def copy(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in checker.COPY_PATHS:
            source = ROOT / relative
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, target)
            else:
                shutil.copy2(source, target)

        class Copied:
            def __enter__(self):
                return root

            def __exit__(self, *_):
                temporary.cleanup()

        return Copied()


if __name__ == "__main__":
    unittest.main()
