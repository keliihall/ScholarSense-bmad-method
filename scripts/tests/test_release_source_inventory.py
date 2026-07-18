from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_cisb import load_json_document  # noqa: E402
from check_release_source import build_git_inventory, validate_release_source_inventory  # noqa: E402


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


if __name__ == "__main__":
    unittest.main()
