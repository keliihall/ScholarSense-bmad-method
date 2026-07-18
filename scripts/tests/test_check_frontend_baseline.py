from __future__ import annotations

import json
import hashlib
import shutil
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_frontend_baseline import (  # noqa: E402
    availability_good_minute,
    availability_monthly_sli,
    validate,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class FrontendBaselineTest(unittest.TestCase):
    def test_approved_production_baseline_passes(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))

    def test_offline_verification_hard_gates_two_clean_replays(self) -> None:
        script = (PROJECT_ROOT / "scripts/verify_frontend.sh").read_text(encoding="utf-8")
        self.assertIn("run_replay replay-1", script)
        self.assertIn("run_replay replay-2", script)
        self.assertIn("cmp -s", script)
        self.assertIn("FRONTEND_REPRODUCIBILITY_DRIFT", script)
        self.assertIn("--ignore-scripts", script)
        self.assertIn('check_frontend_baseline.py" "$replay_root"', script)
        self.assertIn("FRONTEND_INSTALL_SOURCE_MUTATION", script)
        self.assertIn("FRONTEND_SUITE_SOURCE_MUTATION", script)
        self.assertIn("FRONTEND_APPROVED_LOCK_DRIFT", script)

    def test_brand_preflight_is_manifest_driven_and_rebuilds_current_source(self) -> None:
        script = (PROJECT_ROOT / "frontend/scripts/run-brand-preflight.mjs").read_text(encoding="utf-8")
        self.assertNotIn("--expected-version", script)
        for marker in (
            "--brand",
            "--channel",
            "--artifact",
            "--executable",
            "assertApprovedBaselineOracle",
            "APPROVED_ENVIRONMENT_CONTENT_SHA256",
            "APPROVED_ENVIRONMENT_FILE_SHA256",
            "BRAND_ENVIRONMENT_FILE_DRIFT",
            "brandMatrixTarget",
            "artifactSha256",
            "npmExecPath, 'run', 'build'",
            "rmSync(DIST_PATH",
        ):
            self.assertIn(marker, script)

    def test_dependency_ranges_and_unapproved_direct_items_are_rejected(self) -> None:
        with self.fixture() as root:
            package = self.load(root, "frontend/package.json")
            package["dependencies"]["vue"] = "^3.5.40"
            package["dependencies"]["axios"] = "1.13.0"
            self.save(root, "frontend/package.json", package)
            self.assert_reason(root, "DEPENDENCY_VERSION_NOT_EXACT")
            self.assert_reason(root, "DIRECT_DEPENDENCY_NOT_APPROVED")

    def test_toolchain_is_not_modeled_as_a_dependency(self) -> None:
        with self.fixture() as root:
            package = self.load(root, "frontend/package.json")
            package["devDependencies"]["node"] = "24.18.0"
            package["devDependencies"]["npm"] = "11.16.0"
            self.save(root, "frontend/package.json", package)
            self.assert_reason(root, "TOOLCHAIN_AS_NPM_DEPENDENCY")

    def test_package_scripts_are_exact_and_install_lifecycle_hooks_are_rejected(self) -> None:
        with self.fixture() as root:
            package = self.load(root, "frontend/package.json")
            package["scripts"]["postinstall"] = "node -e malicious-fixture"
            self.save(root, "frontend/package.json", package)
            self.assert_reason(root, "PACKAGE_SCRIPT_NOT_APPROVED")

    def test_typescript_alias_and_unique_resolved_compiler_are_enforced(self) -> None:
        with self.fixture() as root:
            package = self.load(root, "frontend/package.json")
            package["devDependencies"]["typescript"] = "6.0.2"
            self.save(root, "frontend/package.json", package)
            lock = self.load(root, "frontend/package-lock.json")
            lock["packages"]["node_modules/rogue/node_modules/typescript"] = {
                "version": "5.9.3",
                "resolved": "https://registry.npmjs.org/typescript/-/typescript-5.9.3.tgz",
                "integrity": "sha512-fixture",
            }
            self.save(root, "frontend/package-lock.json", lock)
            self.assert_reason(root, "TYPESCRIPT_ALIAS_INVALID")
            self.assert_reason(root, "TYPESCRIPT_COMPILER_NOT_UNIQUE")

    def test_missing_or_multiple_locks_are_rejected(self) -> None:
        with self.fixture() as root:
            (root / "frontend/package-lock.json").unlink()
            self.assert_reason(root, "PRODUCTION_LOCK_REQUIRED")
        with self.fixture() as root:
            (root / "yarn.lock").write_text("fixture\n", encoding="utf-8")
            self.assert_reason(root, "MULTIPLE_PACKAGE_LOCKS")

    def test_lock_drift_registry_and_integrity_are_rejected(self) -> None:
        with self.fixture() as root:
            lock = self.load(root, "frontend/package-lock.json")
            lock["packages"][""]["dependencies"]["vue"] = "3.5.39"
            lock["packages"]["node_modules/vue"]["resolved"] = "https://packages.example.invalid/vue.tgz"
            lock["packages"]["node_modules/vue"].pop("integrity", None)
            self.save(root, "frontend/package-lock.json", lock)
            self.assert_reason(root, "LOCK_ROOT_DRIFT")
            self.assert_reason(root, "LOCK_REGISTRY_NOT_APPROVED")
            self.assert_reason(root, "LOCK_INTEGRITY_MISSING")

    def test_lock_entries_require_both_resolved_and_integrity(self) -> None:
        with self.fixture() as root:
            lock = self.load(root, "frontend/package-lock.json")
            lock["packages"]["node_modules/vue"].pop("resolved", None)
            lock["packages"]["node_modules/vue"].pop("integrity", None)
            self.save(root, "frontend/package-lock.json", lock)
            self.assert_reason(root, "LOCK_RESOLVED_MISSING")
            self.assert_reason(root, "LOCK_INTEGRITY_MISSING")

    def test_npmrc_credentials_overrides_and_local_paths_are_rejected(self) -> None:
        cases = (
            "//registry.npmjs.org/:_authToken=secret\n",
            "cache=/Users/example/.npm\n",
            "userconfig=/home/example/.npmrc\n",
            "registry=https://registry.npmjs.org/\noverride=true\n",
        )
        for content in cases:
            with self.subTest(content=content), self.fixture() as root:
                (root / "frontend/.npmrc").write_text(content, encoding="utf-8")
                self.assert_reason(root, "NPMRC_SETTING_NOT_APPROVED")

    def test_companion_approval_remains_provisional_until_story_1_1d(self) -> None:
        with self.fixture() as root:
            path = root / "docs/architecture/adr/frontend-production-baseline.md"
            content = path.read_text(encoding="utf-8").replace("provisional-for-build", "approved")
            path.write_text(content, encoding="utf-8")
            self.assert_reason(root, "ADR_DECISION_MISSING")

    def test_profile_capacity_mix_and_window_invariants_are_enforced(self) -> None:
        with self.fixture() as root:
            profile = self.load(root, "contracts/performance/performance-profile-pp-1.0.0.json")
            profile["capacity"]["students"] = 49_999
            profile["roleMix"]["counselor"] = 69
            profile["scenarioWindow"]["steadyMinutes"] = 59
            self.save(root, "contracts/performance/performance-profile-pp-1.0.0.json", profile)
            self.assert_reason(root, "PROFILE_CAPACITY_DRIFT")
            self.assert_reason(root, "PROFILE_PERCENTAGE_SUM_INVALID")
            self.assert_reason(root, "PROFILE_WINDOW_DRIFT")

    def test_profile_required_sampling_and_attribution_fields_are_enforced(self) -> None:
        with self.fixture() as root:
            profile = self.load(root, "contracts/performance/performance-profile-pp-1.0.0.json")
            del profile["sampling"]["failureSamplePolicy"]
            profile["attributionSegments"].remove("accessibility-ready")
            self.save(root, "contracts/performance/performance-profile-pp-1.0.0.json", profile)
            self.assert_reason(root, "PROFILE_SAMPLING_DRIFT")
            self.assert_reason(root, "PROFILE_ATTRIBUTION_DRIFT")

    def test_recomputed_digest_cannot_hide_same_version_profile_drift(self) -> None:
        with self.fixture() as root:
            profile = self.load(root, "contracts/performance/performance-profile-pp-1.0.0.json")
            profile["sampling"]["failureSamplePolicy"] = "drop-failures"
            profile["sampling"]["duplicatePolicy"] = "last-write-wins"
            profile["sampling"]["scenarioWindowsRequired"] = False
            self.refresh_content_digest(profile)
            self.save(root, "contracts/performance/performance-profile-pp-1.0.0.json", profile)
            self.assert_reason(root, "PROFILE_SAMPLING_DRIFT")
            self.assert_reason(root, "MANIFEST_SCHEMA_VALIDATION")
            self.assert_reason(root, "MANIFEST_VERSION_CONTENT_DRIFT")

    def test_recomputed_digest_cannot_hide_state_boundary_drift(self) -> None:
        with self.fixture() as root:
            baseline = self.load(root, "contracts/performance/frontend-baseline-1.0.0.json")
            baseline["stateBoundary"] = {}
            self.refresh_content_digest(baseline)
            self.save(root, "contracts/performance/frontend-baseline-1.0.0.json", baseline)
            self.assert_reason(root, "FRONTEND_BASELINE_DRIFT: stateBoundary")
            self.assert_reason(root, "MANIFEST_SCHEMA_VALIDATION")
            self.assert_reason(root, "MANIFEST_VERSION_CONTENT_DRIFT")

    def test_profile_network_data_mix_targets_and_guardrails_are_enforced(self) -> None:
        with self.fixture() as root:
            profile = self.load(root, "contracts/performance/performance-profile-pp-1.0.0.json")
            profile["networks"]["campus"]["rttMs"] = 51
            profile["dataDistribution"]["piiPolicy"] = "production-copy"
            profile["requestMix"]["detail"] = 24
            profile["targets"]["contentReadyP95Ms"] = 2001
            profile["guardrails"]["cls"] = 0.11
            self.save(root, "contracts/performance/performance-profile-pp-1.0.0.json", profile)
            self.assert_reason(root, "PROFILE_NETWORK_DRIFT")
            self.assert_reason(root, "PROFILE_DATA_DISTRIBUTION_DRIFT")
            self.assert_reason(root, "PROFILE_REQUEST_MIX_DRIFT")
            self.assert_reason(root, "PROFILE_TARGET_DRIFT")
            self.assert_reason(root, "PROFILE_GUARDRAIL_DRIFT")

    def test_availability_transaction_and_formula_are_executable(self) -> None:
        self.assertTrue(availability_good_minute([True, True, False]))
        self.assertFalse(availability_good_minute([True, False, False]))
        self.assertFalse(availability_good_minute([True, True]))
        self.assertEqual(0.999, availability_monthly_sli(999, 1000))
        with self.assertRaises(ValueError):
            availability_monthly_sli(1, 0)

    def test_availability_steps_timeouts_and_missing_sample_policy_are_enforced(self) -> None:
        with self.fixture() as root:
            policy = self.load(root, "contracts/performance/availability-policy-ap-1.0.0.json")
            policy["transaction"]["steps"].pop()
            policy["transaction"]["steps"][0]["timeoutMs"] = 0
            policy["missingSampleTreatment"] = "excluded"
            self.save(root, "contracts/performance/availability-policy-ap-1.0.0.json", policy)
            self.assert_reason(root, "AVAILABILITY_TRANSACTION_DRIFT")
            self.assert_reason(root, "AVAILABILITY_TIMEOUT_INVALID")
            self.assert_reason(root, "AVAILABILITY_MISSING_SAMPLE_DRIFT")

    def test_browser_versions_placeholders_and_environment_drift_are_rejected(self) -> None:
        with self.fixture() as root:
            environment = self.load(root, "contracts/performance/test-environment-1.0.0.json")
            environment["web"]["chrome"]["current"]["version"] = "latest"
            self.save(root, "contracts/performance/test-environment-1.0.0.json", environment)
            self.assert_reason(root, "ENVIRONMENT_PLACEHOLDER_FORBIDDEN")
            self.assert_reason(root, "MANIFEST_CONTENT_DIGEST_DRIFT")

    def test_browser_os_artifact_url_and_digest_are_immutable(self) -> None:
        with self.fixture() as root:
            environment = self.load(root, "contracts/performance/test-environment-1.0.0.json")
            record = environment["web"]["chrome"]["current"]
            record["artifactUrl"] = "https://unapproved.example.invalid/chrome.zip"
            record["artifactSha256"] = "0" * 64
            record["targetOs"] = "macOS unknown"
            self.refresh_content_digest(environment)
            self.save(root, "contracts/performance/test-environment-1.0.0.json", environment)
            self.assert_reason(root, "BROWSER_ARTIFACT_DRIFT")
            self.assert_reason(root, "MANIFEST_SCHEMA_VALIDATION")
            self.assert_reason(root, "MANIFEST_VERSION_CONTENT_DRIFT")

    def test_mobile_not_applicable_decision_is_explicit_and_auditable(self) -> None:
        with self.fixture() as root:
            environment = self.load(root, "contracts/performance/test-environment-1.0.0.json")
            environment["mobile"]["decisionId"] = ""
            environment["mobile"]["approvedBy"] = ""
            self.save(root, "contracts/performance/test-environment-1.0.0.json", environment)
            self.assert_reason(root, "MOBILE_NA_DECISION_INCOMPLETE")

    def test_schema_required_fields_and_manifest_versions_are_enforced(self) -> None:
        with self.fixture() as root:
            schema = self.load(root, "contracts/performance/test-environment.schema.json")
            schema["required"].remove("mobile")
            environment = self.load(root, "contracts/performance/test-environment-1.0.0.json")
            environment["schemaVersion"] = "2.0.0"
            self.save(root, "contracts/performance/test-environment.schema.json", schema)
            self.save(root, "contracts/performance/test-environment-1.0.0.json", environment)
            self.assert_reason(root, "SCHEMA_REQUIRED_FIELD_DRIFT")
            self.assert_reason(root, "SCHEMA_VERSION_CONTENT_DRIFT")
            self.assert_reason(root, "MANIFEST_SCHEMA_VERSION_MISMATCH")

    def test_manifest_is_validated_against_declared_schema(self) -> None:
        with self.fixture() as root:
            policy = self.load(root, "contracts/performance/availability-policy-ap-1.0.0.json")
            policy["transaction"]["unexpectedBypass"] = True
            self.refresh_content_digest(policy)
            self.save(root, "contracts/performance/availability-policy-ap-1.0.0.json", policy)
            self.assert_reason(root, "MANIFEST_SCHEMA_VALIDATION")
            self.assert_reason(root, "MANIFEST_VERSION_CONTENT_DRIFT")

    def test_manifest_schema_reference_is_required(self) -> None:
        with self.fixture() as root:
            baseline = self.load(root, "contracts/performance/frontend-baseline-1.0.0.json")
            baseline["$schema"] = "./unapproved.schema.json"
            self.refresh_content_digest(baseline)
            self.save(root, "contracts/performance/frontend-baseline-1.0.0.json", baseline)
            self.assert_reason(root, "MANIFEST_SCHEMA_REFERENCE_DRIFT")
            self.assert_reason(root, "MANIFEST_SCHEMA_VALIDATION")

    def test_duplicate_json_keys_are_rejected_at_every_contract_layer(self) -> None:
        cases = (
            ("frontend/package.json", '"name": "@scholarsense/frontend"', '"name": "attacker",\n  "name": "@scholarsense/frontend"', "PACKAGE_JSON_INVALID"),
            ("frontend/package-lock.json", '"lockfileVersion": 3', '"lockfileVersion": 2,\n  "lockfileVersion": 3', "PRODUCTION_LOCK_INVALID"),
            ("contracts/performance/frontend-baseline.schema.json", '"title": "ScholarSense frontend production baseline"', '"title": "attacker",\n  "title": "ScholarSense frontend production baseline"', "SCHEMA_INVALID"),
            ("contracts/performance/performance-profile.schema.json", '"title": "ScholarSense performance profile"', '"title": "attacker",\n  "title": "ScholarSense performance profile"', "SCHEMA_INVALID"),
            ("contracts/performance/availability-policy.schema.json", '"title": "ScholarSense availability policy"', '"title": "attacker",\n  "title": "ScholarSense availability policy"', "SCHEMA_INVALID"),
            ("contracts/performance/test-environment.schema.json", '"title": "ScholarSense test environment"', '"title": "attacker",\n  "title": "ScholarSense test environment"', "SCHEMA_INVALID"),
            ("contracts/performance/frontend-baseline-1.0.0.json", '"schemaVersion": "1.0.0"', '"schemaVersion": "attacker",\n  "schemaVersion": "1.0.0"', "MANIFEST_INVALID"),
            ("contracts/performance/performance-profile-pp-1.0.0.json", '"schemaVersion": "1.0.0"', '"schemaVersion": "attacker",\n  "schemaVersion": "1.0.0"', "MANIFEST_INVALID"),
            ("contracts/performance/availability-policy-ap-1.0.0.json", '"schemaVersion": "1.0.0"', '"schemaVersion": "attacker",\n  "schemaVersion": "1.0.0"', "MANIFEST_INVALID"),
            ("contracts/performance/test-environment-1.0.0.json", '"schemaVersion": "1.0.0"', '"schemaVersion": "attacker",\n  "schemaVersion": "1.0.0"', "MANIFEST_INVALID"),
        )
        for relative, approved, duplicate, reason in cases:
            with self.subTest(relative=relative), self.fixture() as root:
                path = root / relative
                content = path.read_text(encoding="utf-8")
                self.assertIn(approved, content)
                path.write_text(content.replace(approved, duplicate, 1), encoding="utf-8")
                self.assert_reason(root, reason)

    def assert_reason(self, root: Path, reason: str) -> None:
        actual = validate(root)
        self.assertTrue(any(item.startswith(reason) for item in actual), f"expected {reason}, got {actual}")

    @staticmethod
    def load(root: Path, relative: str) -> dict:
        return json.loads((root / relative).read_text(encoding="utf-8"))

    @staticmethod
    def save(root: Path, relative: str, value: dict) -> None:
        (root / relative).write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

    @staticmethod
    def refresh_content_digest(value: dict) -> None:
        payload = dict(value)
        payload.pop("contentSha256", None)
        value["contentSha256"] = hashlib.sha256(
            json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()

    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in ("frontend", "contracts", "docs/architecture/adr"):
            source = PROJECT_ROOT / relative
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, destination)

        class Context:
            def __enter__(self):
                return root

            def __exit__(self, *_args):
                temporary.cleanup()

        return Context()


if __name__ == "__main__":
    unittest.main()
