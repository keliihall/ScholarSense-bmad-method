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
        self.assertTrue(baseline["identities"]["build"].endswith("#workflow:release.yml#job:build-test"))
        self.assertTrue(baseline["identities"]["attestation"].endswith("#workflow:artifact-signing.yml#job:sign"))
        self.assertTrue(baseline["identities"]["webQa"].endswith("#workflow:release.yml#job:formal-web-test"))
        artifact_signer = baseline["identities"]["artifactSigner"]
        manifest_signer = baseline["identities"]["manifestSigner"]
        self.assertNotEqual(artifact_signer, manifest_signer)
        self.assertIn("artifact-signing.yml", artifact_signer)
        self.assertIn("manifest-signing.yml", manifest_signer)
        candidate = copy.deepcopy(baseline)
        candidate["identities"]["attestation"] = candidate["identities"]["attestation"].replace(
            "#job:sign", "#job:sign-artifacts"
        )
        self.assertIn("CISB_IDENTITY_JOB_NOT_FOUND: identities.attestation", validate_cisb(candidate, PROJECT_ROOT))

        for field, value in (
            ("provider", "self-hosted"),
            ("label", "ubuntu-latest"),
            ("imageVersion", "forged-image"),
            ("runtimeImageVersionVariable", "FORGED_IMAGE_VERSION"),
        ):
            with self.subTest(runner_field=field):
                candidate = copy.deepcopy(baseline)
                candidate["runner"][field] = value
                self.assertIn(
                    "CISB_RUNNER_BASELINE_MISMATCH",
                    validate_cisb(candidate, PROJECT_ROOT),
                )

    def test_cisb_rejects_identity_repository_ref_and_duty_misdirection(self) -> None:
        baseline = load_json_document(CISB_PATH)
        cases = {
            "fork repository": (
                "build",
                baseline["identities"]["build"].replace(
                    "repo:keliihall/ScholarSense-bmad-method:",
                    "repo:attacker/ScholarSense-bmad-method:",
                ),
                "CISB_IDENTITY_REPOSITORY_MISMATCH: identities.build",
            ),
            "unprotected ref": (
                "verifier",
                baseline["identities"]["verifier"].replace(
                    "ref:refs/heads/main", "ref:refs/heads/review"
                ),
                "CISB_IDENTITY_REF_MISMATCH: identities.verifier",
            ),
            "existing but wrong job": (
                "build",
                baseline["identities"]["build"].replace(
                    "#job:build-test", "#job:formal-web-test"
                ),
                "CISB_IDENTITY_DUTY_MISMATCH: identities.build",
            ),
            "existing but wrong workflow": (
                "attestation",
                baseline["identities"]["attestation"].replace(
                    "#workflow:artifact-signing.yml#job:sign",
                    "#workflow:release.yml#job:build-test",
                ),
                "CISB_IDENTITY_DUTY_MISMATCH: identities.attestation",
            ),
        }
        for label, (field, identity, expected_issue) in cases.items():
            with self.subTest(label=label):
                candidate = copy.deepcopy(baseline)
                candidate["identities"][field] = identity
                self.assertIn(expected_issue, validate_cisb(candidate, PROJECT_ROOT))

        artifact_fork = copy.deepcopy(baseline)
        artifact_fork["identities"]["artifactSigner"] = artifact_fork["identities"][
            "artifactSigner"
        ].replace("github.com/keliihall/", "github.com/attacker/")
        artifact_fork["signing"]["artifactCertificateIdentity"] = artifact_fork["identities"][
            "artifactSigner"
        ]
        self.assertIn(
            "CISB_SIGNER_REPOSITORY_MISMATCH: identities.artifactSigner",
            validate_cisb(artifact_fork, PROJECT_ROOT),
        )

        promotion_fork = copy.deepcopy(baseline)
        promotion_fork["identities"]["promotion"] = promotion_fork["identities"][
            "promotion"
        ].replace("repo:keliihall/", "repo:attacker/")
        self.assertIn(
            "CISB_PROMOTION_REPOSITORY_MISMATCH",
            validate_cisb(promotion_fork, PROJECT_ROOT),
        )

        synchronized_fork = copy.deepcopy(baseline)
        synchronized_fork["repository"]["url"] = (
            "https://github.com/attacker/ScholarSense-bmad-method"
        )
        for field, value in synchronized_fork["identities"].items():
            synchronized_fork["identities"][field] = value.replace(
                "keliihall/ScholarSense-bmad-method",
                "attacker/ScholarSense-bmad-method",
            )
        synchronized_fork["signing"]["artifactCertificateIdentity"] = synchronized_fork[
            "identities"
        ]["artifactSigner"]
        synchronized_fork["signing"]["manifestCertificateIdentity"] = synchronized_fork[
            "identities"
        ]["manifestSigner"]
        self.assertIn(
            "CISB_REPOSITORY_MISMATCH",
            validate_cisb(synchronized_fork, PROJECT_ROOT),
        )

        synchronized_ref = copy.deepcopy(baseline)
        synchronized_ref["ci"]["protectedRef"] = "refs/heads/review"
        for field, value in synchronized_ref["identities"].items():
            synchronized_ref["identities"][field] = value.replace(
                "refs/heads/main", "refs/heads/review"
            )
        synchronized_ref["signing"]["artifactCertificateIdentity"] = synchronized_ref[
            "identities"
        ]["artifactSigner"]
        synchronized_ref["signing"]["manifestCertificateIdentity"] = synchronized_ref[
            "identities"
        ]["manifestSigner"]
        self.assertIn(
            "CISB_PROTECTED_REF_MISMATCH",
            validate_cisb(synchronized_ref, PROJECT_ROOT),
        )

    def test_cisb_records_the_approved_single_human_plus_automated_web_qa_policy(self) -> None:
        baseline = load_json_document(CISB_PATH)
        approval = baseline["goldenApproval"]
        self.assertEqual("github-user:24710825:keliihall", approval["accountablePrincipal"])
        self.assertEqual("single-accountable-plus-independent-automated-web-qa", approval["policy"])
        self.assertEqual(".github/workflows/release.yml#job:formal-web-test", approval["webQaGate"])
        self.assertIn("actions/runs/{runId}/approvals", approval["approvalHistoryApi"])
        candidate = copy.deepcopy(baseline)
        candidate["goldenApproval"]["webQaGate"] = ".github/workflows/release.yml#job:formal-web"
        self.assertIn("CISB_WEB_QA_GATE_NOT_INDEPENDENT", validate_cisb(candidate, PROJECT_ROOT))

    def test_cisb_vgb_owner_and_executed_web_qa_gate_cannot_drift(self) -> None:
        baseline = load_json_document(CISB_PATH)
        candidate = copy.deepcopy(baseline)
        candidate["goldenApproval"]["accountablePrincipal"] = "github-user:1:attacker"
        self.assertIn(
            "CISB_VGB_UX_BRAND_OWNER_MISMATCH",
            validate_cisb(candidate, PROJECT_ROOT),
        )

        for label in (
            "vgb owner drift",
            "synchronized owner drift",
            "commented web qa",
            "echoed web qa",
            "conditional web qa",
            "conditional web qa job",
        ):
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                workflows = root / ".github/workflows"
                workflows.mkdir(parents=True)
                for source in PROJECT_ROOT.joinpath(".github/workflows").glob("*.yml"):
                    content = source.read_text(encoding="utf-8")
                    if source.name == "release.yml":
                        if label == "commented web qa":
                            content = content.replace(
                                "          ./scripts/run-formal-web-evidence.sh \\",
                                "          # ./scripts/run-formal-web-evidence.sh \\",
                                1,
                            )
                        elif label == "echoed web qa":
                            content = content.replace(
                                "          ./scripts/run-formal-web-evidence.sh \\",
                                "          echo ./scripts/run-formal-web-evidence.sh \\",
                                1,
                            )
                        elif label == "conditional web qa":
                            content = content.replace(
                                "      - name: Verify and test only frozen frontend bytes\n",
                                "      - name: Verify and test only frozen frontend bytes\n"
                                "        if: ${{ false }}\n",
                                1,
                            )
                        elif label == "conditional web qa job":
                            content = content.replace(
                                "  formal-web-test:\n",
                                "  formal-web-test:\n    if: ${{ false }}\n",
                                1,
                            )
                    workflows.joinpath(source.name).write_text(content, encoding="utf-8")
                contracts = root / "contracts/release"
                contracts.mkdir(parents=True)
                vgb = load_json_document(
                    PROJECT_ROOT / "contracts/release/visual-baseline-vgb-1.0.0.json"
                )
                candidate = copy.deepcopy(baseline)
                if label in {"vgb owner drift", "synchronized owner drift"}:
                    vgb["approvedByUxBrand"] = "github-user:1:attacker"
                if label == "synchronized owner drift":
                    candidate["goldenApproval"]["accountablePrincipal"] = (
                        "github-user:1:attacker"
                    )
                contracts.joinpath("visual-baseline-vgb-1.0.0.json").write_text(
                    json.dumps(vgb), encoding="utf-8"
                )
                issues = validate_cisb(candidate, root)
            expected = {
                "vgb owner drift": "CISB_VGB_UX_BRAND_OWNER_MISMATCH",
                "synchronized owner drift": "CISB_VGB_UX_BRAND_OWNER_INVALID",
                "commented web qa": "CISB_WEB_QA_GATE_NOT_EXECUTED",
                "echoed web qa": "CISB_WEB_QA_GATE_NOT_EXECUTED",
                "conditional web qa": "CISB_WEB_QA_GATE_NOT_EXECUTED",
                "conditional web qa job": "CISB_WEB_QA_GATE_NOT_EXECUTED",
            }[label]
            self.assertIn(expected, issues)

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
