from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_cisb import load_json_document  # noqa: E402
from check_release_source import (  # noqa: E402
    INVENTORY_PATH as INVENTORY_RELATIVE_PATH,
    _included,
    build_git_inventory,
    source_scope_issues,
    validate_release_source_inventory,
)
from normalized_manifest import build_manifest  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]
INVENTORY_PATH = PROJECT_ROOT / "contracts/release/release-source-inventory-1.0.0.json"


class ReleaseSourceInventoryTest(unittest.TestCase):
    def test_controlled_inventory_matches_immutable_git_source(self) -> None:
        document = load_json_document(INVENTORY_PATH)
        self.assertEqual([], validate_release_source_inventory(document, PROJECT_ROOT))
        actual = build_git_inventory(PROJECT_ROOT, document["sourceCommit"])
        self.assertEqual(document["gitTreeOid"], actual["gitTreeOid"])
        self.assertEqual(document["normalizedManifestSha256"], actual["normalizedManifestSha256"])
        self.assertEqual(document["fileCount"], actual["fileCount"])

    def test_inventory_rejects_no_vcs_commit_and_digest_drift(self) -> None:
        baseline = load_json_document(INVENTORY_PATH)
        cases = {
            "no vcs": ("sourceCommit", "NO_VCS"),
            "commit drift": ("sourceCommit", "0" * 40),
            "tree drift": ("gitTreeOid", "f" * 40),
            "manifest drift": ("normalizedManifestSha256", "f" * 64),
        }
        for label, (field, value) in cases.items():
            with self.subTest(label=label):
                candidate = copy.deepcopy(baseline)
                candidate[field] = value
                self.assertTrue(validate_release_source_inventory(candidate, PROJECT_ROOT))

    def test_current_source_scope_covers_workflow_release_adr_wrappers_and_build_configuration(self) -> None:
        actual = build_git_inventory(PROJECT_ROOT, "HEAD")
        self.assertEqual([], source_scope_issues(actual["files"]))
        paths = {item["path"] for item in actual["files"]}
        for required in (
            ".github/workflows/platform-probe.yml",
            ".github/workflows/ci.yml",
            ".github/workflows/release.yml",
            ".github/workflows/rollback.yml",
            "backend/mvnw",
            "backend/mvnw.cmd",
            "backend/.mvn/wrapper/maven-wrapper.properties",
            "backend/pom.xml",
            "frontend/package-lock.json",
            "release/manifests.py",
            "release/promotion.py",
            "release/verifier.py",
            "scripts/check_promotion.py",
            "scripts/check_release_manifests.py",
            "scripts/check_release_workflows.py",
            "scripts/promote-release.sh",
            "scripts/read_promotion.py",
            "scripts/rollback-release.sh",
            "scripts/verify-release.sh",
        ):
            self.assertIn(required, paths)

    def test_scope_checker_rejects_omission_self_reference_and_dynamic_evidence(self) -> None:
        actual = build_git_inventory(PROJECT_ROOT, "HEAD")
        omitted = [item for item in actual["files"] if item["path"] != "backend/mvnw"]
        self.assertTrue(any("REQUIRED_PATH_MISSING" in issue for issue in source_scope_issues(omitted)))
        polluted = copy.deepcopy(actual["files"])
        polluted.extend(
            [
                {"path": INVENTORY_RELATIVE_PATH, "sha256": "a" * 64},
                {"path": "release/evidence/runtime.json", "sha256": "b" * 64},
            ]
        )
        issues = source_scope_issues(polluted)
        self.assertTrue(any("SELF_REFERENCE" in issue for issue in issues))
        self.assertTrue(any("DYNAMIC_OUTPUT" in issue for issue in issues))
        self.assertFalse(_included(INVENTORY_RELATIVE_PATH))
        self.assertFalse(_included("release/evidence/runtime.json"))

    def test_normalized_worktree_manifest_excludes_inventory_and_dynamic_release_outputs(self) -> None:
        manifest = build_manifest(PROJECT_ROOT)
        paths = {item["path"] for item in manifest["files"]}
        self.assertNotIn(INVENTORY_RELATIVE_PATH, paths)
        self.assertFalse(any(path.startswith("release/evidence/") for path in paths))


if __name__ == "__main__":
    unittest.main()
