import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OPENAPI = ROOT / "contracts/openapi/quality-recovery-tasks-1.1.openapi.json"


class QualityRecoveryApiContractTest(unittest.TestCase):
    def test_additive_command_paths_are_exact_and_safe(self) -> None:
        document = json.loads(OPENAPI.read_text(encoding="utf-8"))
        self.assertEqual("1.1.0", document["info"]["version"])
        self.assertEqual(7, len(document["paths"]))
        execute = document["paths"]["/quality-recovery-requests/{requestId}/execute"]["post"]
        self.assertIn("IdempotencyKey", json.dumps(execute))
        self.assertEqual({"200", "400", "404", "409", "422", "503"}, set(execute["responses"]))
        serialized = json.dumps(document, separators=(",", ":"))
        for forbidden in ("executionJti", "receiptDigest", "approvalReceipt", "checkerPrincipal"):
            self.assertNotIn(forbidden, serialized)

    def test_all_command_dtos_reject_unknown_fields(self) -> None:
        schemas = json.loads(OPENAPI.read_text(encoding="utf-8"))["components"]["schemas"]
        for name in ("CreateRecoveryRequest", "ExpectedVersion", "ApprovalDecision",
                     "RecoveryRequest", "Execution", "Error"):
            self.assertIs(False, schemas[name]["additionalProperties"], name)
        self.assertIn("taskVersion", schemas["QualityRecoveryTask"]["required"])


if __name__ == "__main__":
    unittest.main()
