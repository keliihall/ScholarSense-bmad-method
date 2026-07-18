from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "release"))

from backend_lock import validate_backend_lock  # noqa: E402
from release_json import load_json  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]
LOCK_PATH = PROJECT_ROOT / "contracts/release/backend-lock-1.0.0.json"


class BackendLockContractTest(unittest.TestCase):
    def test_controlled_backend_lock_covers_runtime_plugins_and_wrapper(self) -> None:
        lock = load_json(LOCK_PATH)
        self.assertGreaterEqual(len(lock["dependencies"]), 40)
        self.assertGreaterEqual(len(lock["plugins"]), 6)
        self.assertEqual([], validate_backend_lock(lock, PROJECT_ROOT))

    def test_backend_lock_rejects_checksum_source_and_dynamic_version_drift(self) -> None:
        baseline = load_json(LOCK_PATH)
        mutations = []
        checksum = copy.deepcopy(baseline)
        checksum["dependencies"][0]["binarySha256"] = "f" * 64
        mutations.append(checksum)
        source = copy.deepcopy(baseline)
        source["plugins"][0]["sourceUri"] = "https://mirror.example/plugin.jar"
        mutations.append(source)
        dynamic = copy.deepcopy(baseline)
        dynamic["dependencies"][0]["coordinate"] = "org.example:bad:LATEST"
        mutations.append(dynamic)
        snapshot = copy.deepcopy(baseline)
        snapshot["plugins"][0]["coordinate"] = "org.example:bad:1.0.0-SNAPSHOT"
        mutations.append(snapshot)
        for lock in mutations:
            with self.subTest(lock=lock):
                self.assertTrue(validate_backend_lock(lock, PROJECT_ROOT))


if __name__ == "__main__":
    unittest.main()
