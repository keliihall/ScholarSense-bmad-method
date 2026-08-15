import copy
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from scripts import check_observability_contract as checker


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "contracts/observability/observability-contract-1.0.0.json"
VALID = ROOT / "contracts/observability/fixtures/valid/full-signal-chain-1.0.0.json"
INVALID = ROOT / "contracts/observability/fixtures/invalid"


class ObservabilityContractTest(unittest.TestCase):
    def load(self, path: Path):
        return json.loads(path.read_text(encoding="utf-8"))

    def test_repository_contract_is_valid_and_digest_bound(self) -> None:
        self.assertEqual([], checker.check(ROOT))
        contract = self.load(CONTRACT)
        self.assertEqual("OBS-1.0.0", contract["contractVersion"])
        self.assertEqual("PP-1.0.0", contract["performanceProfile"]["version"])
        self.assertEqual(
            "sha256:fd66577ba59bdeb90df2dcb3fdbbc94292fa7fbc359893bdfcafcfa09c4f3529",
            contract["performanceProfile"]["contentDigest"],
        )
        self.assertEqual(contract["selfDigest"], checker.self_digest(contract))

    def test_dictionary_freezes_all_signals_and_field_metadata(self) -> None:
        contract = self.load(CONTRACT)
        self.assertEqual(
            {"log", "metric", "span", "event", "privacy", "trustBoundary"},
            set(contract["signals"]),
        )
        required_metadata = {
            "name", "type", "semantics", "source", "owner", "required",
            "cardinality", "sensitivity", "allowedValues", "retention", "export",
        }
        for signal_name in ("log", "metric", "span", "event"):
            fields = contract["signals"][signal_name]["fields"]
            self.assertGreater(len(fields), 0)
            for field in fields:
                self.assertEqual(required_metadata, set(field))
                self.assertTrue(field["source"])
        metadata_surfaces = {
            "log": ("additionalFieldAllowlist", "additionalFields"),
            "metric": ("allowedLabels", "labels"),
        }
        for signal_name, (allowlist_name, metadata_name) in metadata_surfaces.items():
            signal = contract["signals"][signal_name]
            metadata = signal[metadata_name]
            self.assertEqual(signal[allowlist_name], [field["name"] for field in metadata])
            for field in metadata:
                self.assertEqual(required_metadata, set(field))
                self.assertTrue(field["source"])
        span = contract["signals"]["span"]
        attributes = span["attributes"]
        self.assertEqual(
            span["lowCardinalityAttributes"],
            [field["name"] for field in attributes if field["cardinality"] == "low"],
        )
        self.assertEqual(
            span["highCardinalityAttributes"],
            [field["name"] for field in attributes if field["cardinality"] == "high"],
        )
        for field in attributes:
            self.assertEqual(required_metadata, set(field))
            self.assertTrue(field["source"])
        self.assertEqual(
            ["timestamp", "level", "service", "module", "traceId", "event", "code"],
            contract["signals"]["log"]["fixedFields"],
        )
        self.assertNotIn("traceId", contract["signals"]["metric"]["allowedLabels"])
        fields = {field["name"]: field for field in contract["signals"]["log"]["fields"]}
        self.assertEqual(
            ["runtime.lifecycle", "http.request", "job.attempt", "batch.evaluate",
             "outbox.publish", "event.consume", "external.call", "audit.write"],
            fields["event"]["allowedValues"],
        )

    def test_trace_semantics_and_compatibility_are_unambiguous(self) -> None:
        contract = self.load(CONTRACT)
        semantics = contract["traceSemantics"]
        self.assertEqual("immutable-creation-context", semantics["objectCreationTraceId"])
        self.assertEqual("current-operation-context", semantics["traceId"])
        self.assertEqual("w3c-parent-context", semantics["traceparent"])
        self.assertEqual("causal-link-not-parent-rewrite", semantics["spanLink"])
        self.assertEqual("never-rewrite-hash-participating-snapshot", semantics["historyPolicy"])
        propagation = contract["propagation"]
        self.assertEqual("clean-root", propagation["invalidOrAllZero"])
        self.assertEqual("clean-root", propagation["untrustedIngress"])
        self.assertEqual("allowlist-only", propagation["trustedEgress"])
        egress = contract["signals"]["trustBoundary"]["egress"]
        self.assertEqual("APPROVED-AUTHORITY-EGRESS-1.0.0", egress["profileVersion"])
        self.assertEqual("versioned-runtime-authority-profiles", egress["source"])
        self.assertEqual("exact-configured-origin", egress["decision"])
        self.assertEqual("https-only", egress["productionProtocol"])
        self.assertEqual("explicit-loopback-origin-only", egress["developmentSandbox"])
        self.assertNotIn("identity-authority.suda.edu.cn", json.dumps(egress))
        self.assertEqual("forbidden", propagation["baggage"])
        self.assertEqual("dual-write-through-OBS-1.x", propagation["legacyHeader"])
        compatibility = self.load(
            ROOT / "contracts/observability/event-trace-context-compatibility-1.0.0.json"
        )
        self.assertEqual("additive-successor", compatibility["evolution"])
        self.assertEqual("read-only", compatibility["predecessorFixtures"])
        self.assertEqual("same-trace-id-any-valid-parent-span", compatibility["successorReader"])
        self.assertEqual("reader-first-then-single-v2-publish", compatibility["cutover"])
        successor = self.load(ROOT / checker.EVENT_TRACE_CONTRACT)["successor"]
        self.assertEqual("single-v2-event-no-dual-publish", successor["producerCutover"])

    def test_event_successor_keeps_predecessors_read_only_and_covers_all_outcomes(self) -> None:
        contract = self.load(ROOT / checker.EVENT_TRACE_CONTRACT)
        fixture = self.load(ROOT / checker.EVENT_TRACE_OUTCOMES)
        invalid = self.load(ROOT / checker.EVENT_TRACE_INVALID)
        self.assertEqual("read-only", contract["predecessor"]["mutationPolicy"])
        self.assertEqual("forbidden", contract["processing"]["latestStateLookup"])
        self.assertEqual(
            contract["requiredScenarios"],
            [scenario["case"] for scenario in fixture["scenarios"]],
        )
        self.assertTrue(checker._event_traceparent_matches(
            fixture["traceId"], fixture["producerTraceparent"]))
        self.assertEqual(
            fixture["producerTraceparent"], fixture["consumerParentTraceparent"])
        self.assertFalse(checker._event_traceparent_matches(
            invalid["traceId"], invalid["producerTraceparent"]))

    def test_valid_fixture_exercises_each_signal_without_sensitive_values(self) -> None:
        fixture = self.load(VALID)
        self.assertEqual("full-signal-chain", fixture["caseId"])
        self.assertEqual(1, len({item["traceId"] for item in fixture["signals"]}))
        self.assertEqual(7, len(fixture["signals"]))
        self.assertEqual([], checker.scan_sensitive_values(fixture))

    def test_each_invalid_fixture_is_rejected_for_its_declared_reason(self) -> None:
        expected = {
            "unknown-signal.json": "unknown signal",
            "unknown-field.json": "unknown field",
            "unknown-label.json": "unknown metric label",
            "unknown-value.json": "unknown value",
            "performance-profile-drift.json": "performance profile drift",
            "high-cardinality-label.json": "high cardinality metric label",
            "sensitive-value.json": "sensitive value",
            "untrusted-egress.json": "untrusted egress propagation",
        }
        self.assertEqual(expected, {
            path.name: self.load(path)["expectedIssue"] for path in sorted(INVALID.glob("*.json"))
        })
        for filename, issue in expected.items():
            fixture = self.load(INVALID / filename)
            self.assertIn(issue, checker.check_fixture(self.load(CONTRACT), fixture))

    def test_unknown_dictionary_content_and_self_digest_mutations_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copytree(ROOT / "contracts", root / "contracts")
            contract_path = root / checker.CONTRACT
            contract = self.load(contract_path)
            contract["signals"]["metric"]["allowedLabels"].append("studentId")
            contract_path.write_text(json.dumps(contract), encoding="utf-8")
            issues = checker.check(root)
            self.assertTrue(any("metric label allowlist" in issue for issue in issues))
            self.assertTrue(any("self digest" in issue for issue in issues))

    def test_event_outcome_semantic_mutations_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copytree(ROOT / "contracts", root / "contracts")
            outcomes_path = root / checker.EVENT_TRACE_OUTCOMES
            outcomes = self.load(outcomes_path)
            outcomes["scenarios"][3]["expectedAction"] = "skip-gap"
            outcomes_path.write_text(json.dumps(outcomes), encoding="utf-8")

            self.assertIn(
                "complete event ordering idempotency and trace fixture",
                checker.check(root),
            )

    def test_versioned_runtime_profiles_keep_test_and_production_claims_separate(self) -> None:
        profiles = {
            environment: self.load(
                ROOT / f"contracts/config/observability-runtime-{environment}-1.0.0.json"
            )
            for environment in ("dev", "test", "stage", "prod")
        }
        self.assertFalse(profiles["dev"]["otlp"]["enabled"])
        self.assertFalse(profiles["test"]["otlp"]["enabled"])
        self.assertEqual(1.0, profiles["test"]["sampling"]["probability"])
        self.assertEqual("conformance-only", profiles["test"]["runtimeEvidenceClaim"])
        for environment in ("stage", "prod"):
            self.assertTrue(profiles[environment]["otlp"]["enabled"])
            self.assertEqual(
                f"https://otel.{environment}.scholarsense.suda.edu.cn/v1/traces",
                profiles[environment]["otlp"]["endpoint"],
            )
            self.assertNotIn(".invalid", profiles[environment]["otlp"]["endpoint"])
            self.assertEqual(
                f"https://otel.{environment}.scholarsense.suda.edu.cn/v1/metrics",
                profiles[environment]["otlp"]["metricsEndpoint"],
            )
            self.assertEqual("none", profiles[environment]["runtimeEvidenceClaim"])

        bundle = self.load(
            ROOT / "contracts/config/observability-runtime-bundle-1.0.0.json"
        )
        self.assertEqual(["web-api", "worker"], bundle["supportedRoles"])
        self.assertEqual(2048, bundle["exportPolicy"]["maxQueueSize"])
        self.assertEqual(
            ["dev", "test", "stage", "prod"],
            [reference["environment"] for reference in bundle["profiles"]],
        )


if __name__ == "__main__":
    unittest.main()
