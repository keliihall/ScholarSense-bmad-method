import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OPENAPI = ROOT / "contracts/openapi/quality-snapshots.openapi.json"


class QualitySnapshotContractTest(unittest.TestCase):
    def test_openapi_is_assessed_snapshot_only_and_uses_the_shared_http_boundaries(self) -> None:
        document = json.loads(OPENAPI.read_text(encoding="utf-8"))
        self.assertEqual("3.1.2", document["openapi"])
        self.assertEqual([{"url": "/api/v1"}], document["servers"])
        self.assertEqual([{"sessionCookie": []}], document["security"])
        self.assertEqual(
            {
                "/quality-snapshots",
                "/quality-snapshots/{snapshotId}",
                "/quality-snapshots/{snapshotId}/metrics/{metricId}",
            },
            set(document["paths"]),
        )
        self.assertFalse(any(
            method in path
            for path in document["paths"].values()
            for method in ("post", "put", "patch", "delete")
        ))
        self.assertNotIn("DataBatch", json.dumps(document, separators=(",", ":")))
        for path in document["paths"].values():
            operation = path["get"]
            self.assertEqual("no-store", operation["responses"]["200"]["headers"]["Cache-Control"]["schema"]["const"])
            self.assertIn("404", operation["responses"])
            self.assertIn("503", operation["responses"])

    def test_quality_snapshot_schema_matches_the_frozen_42_leaf_projection(self) -> None:
        document = json.loads(OPENAPI.read_text(encoding="utf-8"))
        snapshot = document["components"]["schemas"]["QualitySnapshot"]
        self.assertFalse(snapshot["additionalProperties"])
        self.assertEqual(
            {
                "snapshotId", "batchId", "sourceId", "assessedBatchStatus", "overallResult",
                "observationWindow", "cutoffAt", "evaluatedAt", "watermark", "metricResults",
                "impactScopeCodes", "sourceOwnerRef", "approvalRef", "effectiveAt",
                "retentionScheduleVersion", "qualityMetricDecisionProfileVersion",
                "qualityMetricDecisionProfileDigest", "qualityGateVersion", "qualityGateDigest",
                "canonicalizationProfile", "manifestDigest", "sourceSchemaVersion",
                "sourceSchemaDigest", "immutableHash", "traceId", "lineageId",
                "supersedesSnapshotId", "aggregateVersion",
            },
            set(snapshot["properties"]),
        )
        self.assertEqual(set(snapshot["properties"]), set(snapshot["required"]))
        metric = document["components"]["schemas"]["QualityMetricResult"]
        self.assertEqual(14, len(metric["properties"]))
        self.assertNotIn("rawCount", metric["properties"])
        self.assertEqual(9007199254740991, metric["properties"]["numerator"]["maximum"])
        self.assertEqual(
            "#/components/schemas/UuidV7",
            snapshot["properties"]["snapshotId"]["$ref"],
        )
        self.assertEqual("date-time", snapshot["properties"]["evaluatedAt"]["format"])

    def test_pagination_uuidv7_metric_id_and_offset_timestamp_inputs_are_explicit(self) -> None:
        document = json.loads(OPENAPI.read_text(encoding="utf-8"))
        components = document["components"]
        self.assertEqual(100, components["parameters"]["PageSize"]["schema"]["maximum"])
        self.assertIn("-7[0-9a-f]{3}-", components["schemas"]["UuidV7"]["pattern"])
        self.assertIn("[+-]", components["schemas"]["OffsetTimestamp"]["pattern"])
        metric = components["parameters"]["MetricId"]["schema"]
        self.assertEqual("^[A-Z][A-Z0-9_]{1,127}$", metric["pattern"])
        page = components["schemas"]["QualitySnapshotPage"]
        self.assertEqual({"items", "size", "hasMore", "nextCursor"}, set(page["required"]))


if __name__ == "__main__":
    unittest.main()
