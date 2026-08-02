from __future__ import annotations

import hashlib
import json
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_access_invalidation_contracts import (  # noqa: E402
    delivery_decision,
    event_issues,
    validate,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class AccessInvalidationContractTest(unittest.TestCase):
    def test_production_contracts_and_locks_pass(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))

    def test_responsibility_v1_lock_remains_byte_identical(self) -> None:
        path = (
            PROJECT_ROOT
            / "contracts/responsibility-authority/"
            "responsibility-authority-contract-lock-1.0.0.json"
        )

        self.assertEqual(
            "aaaeee17923104bb7a1ab33c8b15aee7498cea84e69d9072d2afab16077e73df",
            hashlib.sha256(path.read_bytes()).hexdigest(),
        )

    def test_delivery_decision_is_route_scoped_and_fail_closed(self) -> None:
        self.assertEqual("APPLIED", delivery_decision(0, 1, same_payload=True))
        self.assertEqual("DUPLICATE", delivery_decision(1, 1, same_payload=True))
        self.assertEqual("CONFLICT", delivery_decision(1, 1, same_payload=False))
        self.assertEqual("OLD_IGNORED", delivery_decision(3, 2, same_payload=True))
        self.assertEqual("GAP_BACKFILL_REQUIRED", delivery_decision(1, 3, same_payload=True))

    def test_valid_fixture_is_self_contained_and_private(self) -> None:
        path = (
            PROJECT_ROOT
            / "contracts/events/identity-access/fixtures/valid/"
            "responsibility-revoked-v2.json"
        )
        event = json.loads(path.read_text(encoding="utf-8"))

        self.assertEqual([], event_issues(event))
        self.assertEqual(event["id"], event["data"]["eventId"])
        self.assertLessEqual(len(path.read_bytes()), 64 * 1024)

    def test_v2_fixture_validates_its_declared_record_schema_and_digest(self) -> None:
        path = (
            PROJECT_ROOT
            / "contracts/responsibility-authority-v2/fixtures/valid/"
            "responsibility-relation-corrected.json"
        )
        record = json.loads(path.read_text(encoding="utf-8"))
        payload = dict(record["payload"])
        claimed = payload.pop("payloadDigest")
        canonical = json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")

        self.assertEqual("../../responsibility-record.schema.json", record["$schema"])
        self.assertEqual(
            claimed,
            "sha256:" + hashlib.sha256(canonical).hexdigest(),
        )

    def test_consumer_registry_does_not_fake_future_runtime_evidence(self) -> None:
        path = (
            PROJECT_ROOT
            / "contracts/events/identity-access/"
            "consumer-registry-1.0.0.json"
        )
        registry = json.loads(path.read_text(encoding="utf-8"))
        consumers = {item["consumerId"]: item for item in registry["consumers"]}

        current = consumers["authorization-current-scope"]
        self.assertEqual("active", current["lifecycle"])
        self.assertTrue(current["required"])
        self.assertEqual("current-runtime", current["runtimeEvidenceClaim"])

        for consumer_id in (
            "public-task",
            "reporting-export",
            "responsibility-transfer",
        ):
            consumer = consumers[consumer_id]
            self.assertEqual("planned/not-installed", consumer["lifecycle"])
            self.assertFalse(consumer["required"])
            self.assertEqual("none", consumer["runtimeEvidenceClaim"])

        mobile = consumers["mobile-surface-verification"]
        self.assertEqual("surface-verification", mobile["consumerKind"])
        self.assertEqual("none", mobile["runtimeEvidenceClaim"])


if __name__ == "__main__":
    unittest.main()
