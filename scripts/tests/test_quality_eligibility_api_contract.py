import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OPENAPI = ROOT / "contracts/openapi/quality-eligibilities.openapi.json"


class QualityEligibilityApiContractTest(unittest.TestCase):
    def test_read_only_owned_dependency_routes_are_explicit(self) -> None:
        document = json.loads(OPENAPI.read_text(encoding="utf-8"))
        self.assertEqual("3.1.2", document["openapi"])
        self.assertEqual([{"url": "/api/v1"}], document["servers"])
        self.assertEqual([{"sessionCookie": []}], document["security"])
        self.assertEqual(
            {"/quality-eligibilities", "/quality-eligibilities/{eligibilityId}"},
            set(document["paths"]),
        )
        for path in document["paths"].values():
            self.assertEqual({"get"}, set(path))
            response = path["get"]["responses"]
            self.assertEqual(
                "no-store", response["200"]["headers"]["Cache-Control"]["schema"]["const"]
            )
            self.assertIn("404", response)
            self.assertIn("503", response)

    def test_projection_is_self_contained_and_excludes_hidden_fields(self) -> None:
        document = json.loads(OPENAPI.read_text(encoding="utf-8"))
        schema = document["components"]["schemas"]["QualityEligibility"]
        self.assertFalse(schema["additionalProperties"])
        self.assertEqual(set(schema["properties"]), set(schema["required"]))
        self.assertEqual(
            {
                "eligibilityId", "ruleId", "ruleVersion", "status", "reasonCode",
                "operator", "threshold", "members", "failedMembers", "registryVersion",
                "aggregateVersion", "effectiveAt", "occurredAt",
            },
            set(schema["properties"]),
        )
        member = document["components"]["schemas"]["QualityEligibilityMember"]
        self.assertEqual(set(member["properties"]), set(member["required"]))
        serialized = json.dumps(document, separators=(",", ":"))
        self.assertNotIn("traceId", schema["properties"])
        for hidden in ("studentId", "rawRecord", "metricResults", "deliveryStatus"):
            self.assertNotIn(hidden, serialized)

    def test_keyset_pagination_uuidv7_and_offset_timestamps_are_bounded(self) -> None:
        components = json.loads(OPENAPI.read_text(encoding="utf-8"))["components"]
        self.assertEqual(100, components["parameters"]["PageSize"]["schema"]["maximum"])
        self.assertIn("-7[0-9a-f]{3}-", components["schemas"]["UuidV7"]["pattern"])
        self.assertIn("[+-]", components["schemas"]["OffsetTimestamp"]["pattern"])
        page = components["schemas"]["QualityEligibilityPage"]
        self.assertEqual({"items", "size", "hasMore", "nextCursor"}, set(page["required"]))


if __name__ == "__main__":
    unittest.main()
