from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_cisb import load_json_document, validate_cisb  # noqa: E402
from check_workflow_security import validate_workflow  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]
CISB_PATH = PROJECT_ROOT / "contracts/release/ci-supply-chain-baseline-1.0.0.json"


class ReleasePlatformContractTest(unittest.TestCase):
    def test_approved_cisb_is_complete_and_machine_verifiable(self) -> None:
        document = load_json_document(CISB_PATH)
        self.assertEqual([], validate_cisb(document, PROJECT_ROOT))

    def test_cisb_rejects_placeholders_and_unproven_capabilities(self) -> None:
        baseline = load_json_document(CISB_PATH)
        cases = {
            "placeholder": ("repository", "url", "TBD"),
            "no vcs": ("repository", "sourceCommit", "NO_VCS"),
            "missing proof": ("capabilityEvidence", "promotionCas", ""),
            "mutable tool": ("toolchain", "cosign", "latest"),
        }
        for label, (section, field, value) in cases.items():
            with self.subTest(label=label):
                candidate = copy.deepcopy(baseline)
                candidate[section][field] = value
                issues = validate_cisb(candidate, PROJECT_ROOT)
                self.assertTrue(issues, "invalid CISB unexpectedly passed")
                self.assertTrue(
                    any(issue.startswith("CISB_PLATFORM_BASELINE_INCOMPLETE") for issue in issues),
                    issues,
                )

    def test_cisb_json_rejects_duplicate_keys_and_bom(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "cisb.json"
            path.write_text('{"version":"CISB-1.0.0","version":"forged"}\n', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "JSON_DUPLICATE_KEY"):
                load_json_document(path)
            path.write_bytes(b'\xef\xbb\xbf{"version":"CISB-1.0.0"}\n')
            with self.assertRaisesRegex(ValueError, "JSON_BOM_FORBIDDEN"):
                load_json_document(path)

    def test_platform_workflow_uses_pinned_actions_and_minimum_permissions(self) -> None:
        workflow = PROJECT_ROOT / ".github/workflows/platform-probe.yml"
        self.assertEqual([], validate_workflow(workflow))

    def test_cisb_binds_the_actual_release_trust_contract(self) -> None:
        baseline = load_json_document(CISB_PATH)
        self.assertEqual(".github/workflows/release.yml", baseline["ci"]["workflow"])
        self.assertEqual("ImageVersion", baseline["runner"]["runtimeImageVersionVariable"])
        artifact_signer = baseline["identities"]["artifactSigner"]
        manifest_signer = baseline["identities"]["manifestSigner"]
        self.assertNotEqual(artifact_signer, manifest_signer)
        self.assertIn("artifact-signing.yml", artifact_signer)
        self.assertIn("manifest-signing.yml", manifest_signer)

    def test_cisb_records_the_approved_single_human_plus_automated_web_qa_policy(self) -> None:
        baseline = load_json_document(CISB_PATH)
        approval = baseline["goldenApproval"]
        self.assertEqual("github-user:24710825:keliihall", approval["accountablePrincipal"])
        self.assertEqual("single-accountable-plus-independent-automated-web-qa", approval["policy"])
        self.assertEqual(".github/workflows/release.yml#job:formal-web", approval["webQaGate"])
        self.assertIn("actions/runs/{runId}/approvals", approval["approvalHistoryApi"])

    def test_workflow_rejects_mutable_action_write_all_and_secret_sink(self) -> None:
        cases = {
            "mutable action": "uses: actions/checkout@v4\n",
            "write all": "permissions: write-all\n",
            "pull request target": "on: pull_request_target\npermissions:\n  contents: write\n",
            "secret echo": "run: echo ${{ secrets.RELEASE_TOKEN }}\n",
            "unpinned image": "container: aquasec/trivy:latest\n",
        }
        for label, content in cases.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                workflow = Path(directory) / "bad.yml"
                workflow.write_text(f"name: bad\n{content}", encoding="utf-8")
                self.assertTrue(validate_workflow(workflow))


if __name__ == "__main__":
    unittest.main()
