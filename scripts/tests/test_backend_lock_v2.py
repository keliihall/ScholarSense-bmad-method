from __future__ import annotations

import hashlib
import json
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "release"))
sys.path.insert(0, str(ROOT / "scripts"))

from backend_lock import validate_backend_lock  # noqa: E402
from release_json import schema_issues  # noqa: E402


class BackendLockV2ContractTest(unittest.TestCase):
    def test_v2_adds_otel_graph_and_preserves_v1_bytes(self) -> None:
        predecessor = ROOT / "contracts/release/backend-lock-1.0.0.json"
        self.assertEqual(
            "c71982099779c38bcdb2ccf922367ae329f12f79ec6de6606063dc12e8032b01",
            hashlib.sha256(predecessor.read_bytes()).hexdigest(),
        )
        lock = json.loads((
            ROOT / "contracts/release/backend-lock-2.0.0.json"
        ).read_text(encoding="utf-8"))
        schema = json.loads((
            ROOT / "contracts/release/backend-lock-2.schema.json"
        ).read_text(encoding="utf-8"))
        coordinates = {item["coordinate"] for item in lock["dependencies"]}
        self.assertEqual("BACKEND-LOCK-2.0.0", lock["version"])
        self.assertIn(
            "io.opentelemetry:opentelemetry-exporter-otlp:1.62.0", coordinates
        )
        self.assertIn(
            "io.micrometer:micrometer-tracing-bridge-otel:1.7.0", coordinates
        )
        self.assertEqual([], schema_issues(lock, schema))
        self.assertEqual([], validate_backend_lock(lock, ROOT))


if __name__ == "__main__":
    unittest.main()
