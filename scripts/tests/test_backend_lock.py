from __future__ import annotations

import copy
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "release"))

from backend_lock import CENTRAL, validate_backend_lock  # noqa: E402
from prepare_locked_maven_plugin import prepare  # noqa: E402
from release_json import load_json  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]
LOCK_PATH = PROJECT_ROOT / "contracts/release/backend-lock-1.0.0.json"


class BackendLockContractTest(unittest.TestCase):
    def test_controlled_backend_lock_covers_runtime_plugins_and_wrapper(self) -> None:
        lock = load_json(LOCK_PATH)
        self.assertGreaterEqual(len(lock["dependencies"]), 40)
        self.assertGreaterEqual(len(lock["plugins"]), 7)
        self.assertEqual([], validate_backend_lock(lock, PROJECT_ROOT))
        self.assertGreaterEqual(len(lock["pluginResolution"]), 7)
        self.assertGreater(sum(len(item["artifacts"]) for item in lock["pluginResolution"]), 40)
        self.assertEqual([], lock["extensions"])

    def test_cold_cache_dependency_plugin_is_versioned_and_transitively_locked(self) -> None:
        lock = load_json(LOCK_PATH)
        coordinate = "org.apache.maven.plugins:maven-dependency-plugin:3.10.0"
        self.assertEqual(coordinate, lock.get("bootstrapPlugin"))
        roots = {item["root"]: item["artifacts"] for item in lock["pluginResolution"]}
        self.assertIn(coordinate, roots)
        self.assertGreater(len(roots[coordinate]), 10)
        self.assertTrue(
            all(item.get("binarySha256") and item.get("pomSha256") for item in roots[coordinate])
        )

        candidate = copy.deepcopy(lock)
        candidate["pluginResolution"] = [
            item for item in candidate["pluginResolution"] if item["root"] != coordinate
        ]
        self.assertIn(
            "BACKEND_LOCK_BOOTSTRAP_PLUGIN_RESOLUTION_MISSING",
            validate_backend_lock(candidate, PROJECT_ROOT),
        )

        incomplete = copy.deepcopy(lock)
        bootstrap = next(
            item for item in incomplete["pluginResolution"] if item["root"] == coordinate
        )
        bootstrap["artifacts"] = [
            item
            for item in bootstrap["artifacts"]
            if item["coordinate"] != "org.tukaani:xz:jar:1.11"
        ]
        self.assertIn(
            "BACKEND_LOCK_BOOTSTRAP_PLUGIN_GRAPH_DRIFT",
            validate_backend_lock(incomplete, PROJECT_ROOT),
        )

    def test_cold_cache_preparer_fetches_verifies_and_then_reuses_locked_bytes(self) -> None:
        source_repository = Path.home() / ".m2/repository"

        class Response:
            def __init__(self, uri: str, payload: bytes) -> None:
                self.uri = uri
                self.payload = payload
                self.offset = 0

            def __enter__(self) -> Response:
                return self

            def __exit__(self, *_args: object) -> None:
                return None

            def geturl(self) -> str:
                return self.uri

            def read(self, size: int) -> bytes:
                block = self.payload[self.offset : self.offset + size]
                self.offset += len(block)
                return block

        def fetch(uri: str, *, timeout: int) -> Response:
            self.assertEqual(60, timeout)
            source = source_repository / uri.removeprefix(CENTRAL)
            return Response(uri, source.read_bytes())

        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory) / "repository"
            with mock.patch(
                "prepare_locked_maven_plugin.urllib.request.urlopen", side_effect=fetch
            ) as urlopen:
                self.assertEqual(43, prepare(PROJECT_ROOT, repository))
                self.assertGreater(urlopen.call_count, 80)
            with mock.patch(
                "prepare_locked_maven_plugin.urllib.request.urlopen",
                side_effect=AssertionError("warm replay must not use the network"),
            ):
                self.assertEqual(43, prepare(PROJECT_ROOT, repository))

            locked_jar = repository / (
                "org/apache/maven/plugins/maven-dependency-plugin/3.10.0/"
                "maven-dependency-plugin-3.10.0.jar"
            )
            locked_jar.write_bytes(b"tampered")
            with self.assertRaisesRegex(
                ValueError, "BACKEND_LOCK_BOOTSTRAP_PLUGIN_LOCAL_DIGEST_MISMATCH"
            ):
                prepare(PROJECT_ROOT, repository)

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
