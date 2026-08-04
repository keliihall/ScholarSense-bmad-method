from __future__ import annotations

import copy
import hashlib
import json
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_public_integration_contracts import (  # noqa: E402
    MAX_SAFE_INTEGER,
    audit_record_issues,
    canonical_digest,
    delivery_decision,
    derive_delivery_intent_id,
    derive_generation_key,
    event_issues,
    execute_compatibility_fixture,
    execute_negative_fixture,
    materialize_audit_record,
    privacy_surface_issues,
    validate,
)
from release_json import load_json, schema_issues  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]
CONTRACT_ROOT = PROJECT_ROOT / "contracts/public-integration"
EVENT_FIXTURE = (
    CONTRACT_ROOT / "fixtures/valid/public-task-created-event.json"
)


class PublicIntegrationContractTest(unittest.TestCase):
    def test_contract_package_and_lock_pass(self) -> None:
        self.assertEqual([], validate(PROJECT_ROOT))

    def test_command_payload_accepts_approved_task_message_and_writeback_fields(self):
        schema = load_json(
            PROJECT_ROOT
            / "contracts/events/public-integration/public-integration-command.schema.json"
        )
        base = {
            "contractVersion": "PIC-1.0.0",
            "channelId": "pic.public-task.v1",
            "generationKey": "g1." + "a" * 43,
            "providerEffectKey": "pe1." + "b" * 43,
            "operation": "create",
            "aggregateType": "Candidate",
            "aggregateId": "candidate-01",
            "aggregateVersion": 1,
            "requestDigest": "sha256:" + "c" * 64,
            "traceId": "d" * 32,
        }
        cases = (
            ("pic.public-task.v1", "create", {
                "workItemKey": "wk1.synthetic", "itemType": "candidate",
                "ownerRef": "owner1.synthetic", "priority": "urgent",
                "dueAt": "2026-08-04T08:00:00Z", "progressState": "pending",
                "taskStatus": "open", "deepLinkRouteId": "care-work-item",
                "routeState": "item-01",
            }),
            ("pic.public-message.v1", "create", {
                "notificationType": "overdue", "escalationVersion": 1,
            }),
            ("pic.status-result-writeback.v1", "writeback", {
                "localAggregateId": "candidate-01", "localAggregateVersion": 2,
                "eventId": "019fc688-380d-7391-b3d0-877e0f9b3026",
                "status": "confirmed", "occurredAt": "2026-08-03T08:00:00Z",
                "resultCategory": "accepted", "correlationId": "corr-01",
                "causationId": "cause-01",
            }),
        )
        for channel_id, operation, payload in cases:
            with self.subTest(channel=channel_id, payload=sorted(payload)):
                command = {
                    **base,
                    "channelId": channel_id,
                    "operation": operation,
                    "payload": payload,
                }
                self.assertEqual([], schema_issues(command, schema))

        invalid_commands = (
            {**base, "payload": {}},
            {
                **base,
                "payload": {
                    **cases[0][2],
                    "notificationType": "overdue",
                    "escalationVersion": 1,
                },
            },
            {
                **base,
                "channelId": "pic.public-message.v1",
                "payload": cases[0][2],
            },
            {
                **base,
                "channelId": "pic.status-result-writeback.v1",
                "operation": "create",
                "payload": cases[2][2],
            },
            {**base, "payload": {"freeText": "forbidden"}},
        )
        for command in invalid_commands:
            with self.subTest(invalid=command["payload"]):
                self.assertTrue(schema_issues(command, schema))

    def test_operation_receipt_and_audit_profiles_lock_required_tokens(self):
        openapi = load_json(
            PROJECT_ROOT / "contracts/openapi/public-integration.openapi.json"
        )
        receipt = openapi["components"]["schemas"]["OperationReceipt"]
        self.assertIn("idempotencyScopeToken", receipt["required"])
        self.assertFalse(receipt["additionalProperties"])
        audit = load_json(
            CONTRACT_ROOT / "public-integration-audit-profile-1.0.0.json"
        )
        self.assertIn("eventPayloadDigestToken", audit["recordShape"]["streamFields"])
        self.assertIn("requestDigestToken", audit["recordShape"]["intentFields"])

    def test_negative_and_compatibility_fixtures_execute_their_declared_inputs(self):
        fixture = load_json(
            CONTRACT_ROOT / "fixtures/invalid/negative-fixtures-1.0.0.json"
        )
        valid_event = load_json(EVENT_FIXTURE)
        for case in fixture["cases"]:
            with self.subTest(case=case["id"]):
                self.assertIn("input", case)
                self.assertEqual(
                    case["expectedCode"],
                    execute_negative_fixture(
                        case, valid_event=valid_event, project_root=PROJECT_ROOT
                    ),
                )
        for name in ("optional-addition-1.1.0.json", "breaking-without-major.json"):
            document = load_json(CONTRACT_ROOT / "fixtures/compatibility" / name)
            with self.subTest(compatibility=name):
                self.assertEqual(document["expected"], execute_compatibility_fixture(document))

        optional = load_json(
            CONTRACT_ROOT / "fixtures/compatibility/optional-addition-1.1.0.json"
        )
        invented = copy.deepcopy(optional)
        invented["field"] = "totallyInvented"
        self.assertEqual(
            "PIC_COMPATIBILITY_FIXTURE_INVALID",
            execute_compatibility_fixture(invented),
        )
        false_projection = copy.deepcopy(optional)
        false_projection["expectedDownProjectedInstance"]["payload"][
            "notificationType"
        ] = "due"
        self.assertEqual(
            "PIC_COMPATIBILITY_FIXTURE_INVALID",
            execute_compatibility_fixture(false_projection),
        )
        breaking = load_json(
            CONTRACT_ROOT / "fixtures/compatibility/breaking-without-major.json"
        )
        nonexistent_required = copy.deepcopy(breaking)
        nonexistent_required["field"] = "notARealRequiredField"
        self.assertEqual(
            "PIC_COMPATIBILITY_FIXTURE_INVALID",
            execute_compatibility_fixture(nonexistent_required),
        )

    def test_registry_freezes_five_capabilities_and_one_mode_per_lane(self) -> None:
        registry = json.loads(
            (CONTRACT_ROOT / "adapter-registry-1.0.0.json").read_text(
                encoding="utf-8"
            )
        )
        descriptors = registry["descriptors"]
        self.assertEqual(5, len(descriptors))
        self.assertEqual(
            {
                "public-task",
                "public-message",
                "status-result-writeback",
                "external-transfer-work-order",
                "metric-publication",
            },
            {item["capability"] for item in descriptors},
        )
        lanes = {
            (item["channelId"], item["contractMajor"]): item["orderingMode"]
            for item in descriptors
        }
        self.assertEqual(len(descriptors), len(lanes))
        self.assertTrue(
            set(lanes.values()) <= {"aggregate-stream", "intent-command"}
        )

    def test_canonical_id_derivations_are_stable_and_pii_free(self) -> None:
        intent_id = derive_delivery_intent_id(
            "wk1.2L8GfM3v",
            "overdue",
            2,
        )
        generation = derive_generation_key(
            provenance_mode="intent-command",
            delivery_intent_id=intent_id,
        )
        self.assertRegex(intent_id, r"^di1\.[A-Za-z0-9_-]{43}$")
        self.assertRegex(generation, r"^g1\.[A-Za-z0-9_-]{43}$")
        self.assertNotIn("student", intent_id.lower())
        self.assertEqual(
            generation,
            derive_generation_key(
                provenance_mode="intent-command",
                delivery_intent_id=intent_id,
            ),
        )

    def test_valid_event_is_strict_self_consistent_and_within_wire_limit(self) -> None:
        event = json.loads(EVENT_FIXTURE.read_text(encoding="utf-8"))
        self.assertEqual([], event_issues(event, project_root=PROJECT_ROOT))
        self.assertEqual(event["id"], event["data"]["eventId"])
        self.assertEqual(event["time"], event["data"]["occurredAt"])
        self.assertLessEqual(
            len(json.dumps(event, separators=(",", ":")).encode("utf-8")),
            65_536,
        )

    def test_event_rejects_unknown_keys_mismatch_compression_and_oversize(self) -> None:
        event = json.loads(EVENT_FIXTURE.read_text(encoding="utf-8"))
        unknown = copy.deepcopy(event)
        unknown["unexpected"] = True
        self.assertIn(
            "EVENT_SCHEMA_REJECTED",
            event_issues(unknown, project_root=PROJECT_ROOT),
        )

        mismatched = copy.deepcopy(event)
        mismatched["data"]["eventId"] = "018f0f9a-7b0d-7abc-8def-0123456789ad"
        self.assertIn(
            "EVENT_ID_MISMATCH",
            event_issues(mismatched, project_root=PROJECT_ROOT),
        )

        compressed = copy.deepcopy(event)
        compressed["contentEncoding"] = "gzip"
        self.assertIn(
            "EVENT_CONTENT_ENCODING_UNSUPPORTED",
            event_issues(compressed, project_root=PROJECT_ROOT),
        )

        oversized = copy.deepcopy(event)
        oversized["data"]["payload"]["routeState"] = "a" * 65_537
        self.assertIn(
            "EVENT_PAYLOAD_TOO_LARGE",
            event_issues(oversized, project_root=PROJECT_ROOT),
        )

    def test_route_ordering_separates_sparse_source_version_from_sequence(self) -> None:
        self.assertEqual(
            "NEXT", delivery_decision(0, 1, None, 4, same_identity=True)
        )
        self.assertEqual(
            "DUPLICATE", delivery_decision(1, 1, 4, 4, same_identity=True)
        )
        self.assertEqual(
            "CONFLICT", delivery_decision(1, 1, 4, 4, same_identity=False)
        )
        self.assertEqual(
            "STALE", delivery_decision(3, 2, 9, 4, same_identity=True)
        )
        self.assertEqual(
            "GAP", delivery_decision(1, 3, 4, 9, same_identity=True)
        )
        self.assertEqual(
            "SOURCE_VERSION_CONFLICT",
            delivery_decision(1, 2, 4, 4, same_identity=True),
        )
        self.assertEqual(MAX_SAFE_INTEGER, 9_007_199_254_740_991)
        self.assertEqual(
            "INVALID", delivery_decision(0, 0, None, 1, same_identity=True)
        )
        self.assertEqual(
            "NEXT", delivery_decision(0, 1, None, 1, same_identity=True)
        )
        self.assertEqual(
            "NEXT",
            delivery_decision(
                MAX_SAFE_INTEGER - 1,
                MAX_SAFE_INTEGER,
                MAX_SAFE_INTEGER - 1,
                MAX_SAFE_INTEGER,
                same_identity=True,
            ),
        )
        self.assertEqual(
            "INVALID",
            delivery_decision(
                MAX_SAFE_INTEGER,
                MAX_SAFE_INTEGER + 1,
                MAX_SAFE_INTEGER,
                MAX_SAFE_INTEGER + 1,
                same_identity=True,
            ),
        )

    def test_tsp_mpp_binding_uses_approved_source_and_digest(self) -> None:
        lock = json.loads(
            (
                CONTRACT_ROOT
                / "public-integration-contract-lock-1.0.0.json"
            ).read_text(encoding="utf-8")
        )
        source = (
            PROJECT_ROOT
            / "_bmad-output/planning-artifacts/"
            "delegated-decision-baseline-2026-07-17.md"
        )
        bindings = {item["id"]: item for item in lock["sourceBindings"]}
        self.assertEqual(
            hashlib.sha256(source.read_bytes()).hexdigest(),
            bindings["TransferSla"]["sourceSha256"],
        )
        self.assertEqual("TSP-1.0.0", bindings["TransferSla"]["version"])
        self.assertEqual(
            "MPP-1.0.0", bindings["MetricPublication"]["version"]
        )

    def test_digest_domains_remain_explicit(self) -> None:
        semantic = {"kind": "event", "sourceFactId": "x", "source": "urn:x"}
        digest = canonical_digest(semantic)
        self.assertRegex(digest, r"^sha256:[0-9a-f]{64}$")
        self.assertEqual(digest, canonical_digest(semantic))

    def test_test_scope_audit_vocabulary_covers_every_required_path(self) -> None:
        profile = json.loads((
            CONTRACT_ROOT / "public-integration-audit-profile-1.0.0.json"
        ).read_text(encoding="utf-8"))
        fixture = json.loads((
            CONTRACT_ROOT / "fixtures/audit/conformance-records-1.0.0.json"
        ).read_text(encoding="utf-8"))
        self.assertEqual(
            {
                "accepted", "rejected", "retry", "confirmed", "failed",
                "reconcile", "replay", "gap", "poison",
            },
            {item["path"] for item in profile["actionVocabulary"]},
        )
        for case in fixture["records"]:
            with self.subTest(case=case["id"]):
                record = materialize_audit_record(fixture, case)
                self.assertEqual([], audit_record_issues(record, profile))
        for case in fixture["negativeCases"]:
            with self.subTest(case=case["id"]):
                record = materialize_audit_record(fixture, case)
                self.assertIn(
                    case["expectedIssue"], audit_record_issues(record, profile)
                )

    def test_observability_profile_is_low_cardinality_and_privacy_closed(self) -> None:
        profile = json.loads((
            CONTRACT_ROOT / "public-integration-audit-profile-1.0.0.json"
        ).read_text(encoding="utf-8"))
        self.assertEqual([], privacy_surface_issues(profile))
        self.assertEqual("none", profile["productionEmitter"])
        self.assertEqual("none", profile["auditSuccessorVersion"])
        self.assertFalse(
            profile["productionBoundary"][
                "syntheticEvidenceIncludedInRuntimeDenominator"
            ]
        )
        unsafe = copy.deepcopy(profile)
        unsafe["observability"]["metrics"]["allowedLabels"].append("traceId")
        self.assertIn(
            "PIC_AUDIT_METRIC_LABEL_HIGH_CARDINALITY",
            privacy_surface_issues(unsafe),
        )


if __name__ == "__main__":
    unittest.main()
