from __future__ import annotations

import sys
import tempfile
import unittest
import json
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_release_workflows import validate_release_workflows  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class ReleaseWorkflowContractTest(unittest.TestCase):
    def test_pr_ci_and_protected_release_workflows_enforce_the_lifecycle(self) -> None:
        self.assertEqual([], validate_release_workflows(PROJECT_ROOT))

    def test_golden_approval_is_separate_from_release_and_uses_exact_runner(self) -> None:
        golden = (PROJECT_ROOT / ".github/workflows/golden-approval.yml").read_text(encoding="utf-8")
        release = (PROJECT_ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("capture-formal-web-goldens.mjs", golden)
        self.assertNotIn("capture-formal-web-goldens.mjs", release)
        self.assertIn("[self-hosted, macOS, ARM64, scholarsense-test-env-1]", golden)
        self.assertNotIn("update-snapshots", golden + release)

    def test_dispatch_inputs_are_never_interpolated_into_privileged_shell_source(self) -> None:
        release = (PROJECT_ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        rollback = (PROJECT_ROOT / ".github/workflows/rollback.yml").read_text(encoding="utf-8")
        for workflow in (release, rollback):
            lines = workflow.splitlines()
            for index, line in enumerate(lines):
                if not line.lstrip().startswith("run:"):
                    continue
                indent = len(line) - len(line.lstrip())
                block: list[str] = []
                for candidate in lines[index + 1 :]:
                    if candidate.strip() and len(candidate) - len(candidate.lstrip()) <= indent:
                        break
                    block.append(candidate)
                self.assertNotIn("${{ inputs.", "\n".join(block))

    def test_checker_rejects_pr_write_oidc_order_bypass_and_secret_execution(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workflows = root / ".github/workflows"
            workflows.mkdir(parents=True)
            (workflows / "ci.yml").write_text(
                "name: ci\non: pull_request\npermissions:\n  contents: write\njobs:\n  verify:\n    runs-on: ubuntu-24.04\n    steps:\n      - run: echo ${{ secrets.RELEASE_KEY }}\n",
                encoding="utf-8",
            )
            (workflows / "release.yml").write_text(
                "name: release\non: pull_request\npermissions: {}\njobs:\n  artifact-attestation:\n    needs: sbom-scan\n    permissions:\n      id-token: write\n  build-cas:\n    needs: artifact-attestation\n",
                encoding="utf-8",
            )
            issues = validate_release_workflows(root)
            self.assertTrue(any("CI_WRITE_PERMISSION" in issue for issue in issues))
            self.assertTrue(any("CI_SECRET_CONTEXT" in issue for issue in issues))
            self.assertTrue(any("RELEASE_TRIGGER" in issue for issue in issues))
            self.assertTrue(any("RELEASE_JOB_ORDER" in issue for issue in issues))

    def test_release_tool_installer_verifies_upstream_bundles_and_locked_bytes(self) -> None:
        installer = (PROJECT_ROOT / "scripts/install-release-tools.sh").read_text(encoding="utf-8")
        self.assertIn("check_release_tool.py", installer)
        self.assertIn("cosign-linux-amd64-bundle", installer)
        self.assertIn("trivy-linux-amd64-bundle", installer)
        self.assertIn("https://accounts.google.com", installer)
        self.assertIn("reusable-release.yaml@refs/tags/v0.72.0", installer)
        lock = json.loads((PROJECT_ROOT / "contracts/release/toolchain-lock-1.0.0.json").read_text(encoding="utf-8"))
        tools = {item["name"]: item for item in lock["tools"]}
        self.assertEqual("fdaa1c168d67041cd0d8f5782f8136ac5d148827b6911ba8bb577cbc7e13de2c", tools["cosign-linux-amd64-bundle"]["binarySha256"])
        self.assertEqual("fccbe7d4877af44f27e205528626dfeb3ff6efac57c22061f1fccb59e8a80007", tools["trivy-linux-amd64-bundle"]["binarySha256"])

    def test_manifest_version_is_globally_bound_before_signing(self) -> None:
        release = (PROJECT_ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        binding = release.index("release/version_binding.py")
        signing = release.index("  manifest-signing:")
        self.assertLess(binding, signing)

    def test_selected_signatures_use_distinct_reusable_workflow_identities_and_spdx(self) -> None:
        release = (PROJECT_ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        verifier = (PROJECT_ROOT / "scripts/verify-release.sh").read_text(encoding="utf-8")
        self.assertIn("uses: ./.github/workflows/artifact-signing.yml", release)
        self.assertIn("uses: ./.github/workflows/manifest-signing.yml", release)
        self.assertNotIn("  artifact-attestation:", release)
        self.assertNotIn("  manifest-signature:", release)
        self.assertIn("https://spdx.dev/Document", verifier)

    def test_build_and_formal_tests_are_isolated_from_publish_permissions(self) -> None:
        release = (PROJECT_ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("  build-test:", release)
        self.assertIn("  formal-web-test:", release)
        self.assertIn("build-transfer.sha256", release)
        self.assertIn("formal-web-transfer.sha256", release)


if __name__ == "__main__":
    unittest.main()
