from __future__ import annotations

import copy
import hashlib
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import check_quality_snapshot_deletion_result_1_1 as checker  # noqa: E402
from release_json import canonical_bytes, load_json, schema_definition_issues, schema_issues  # noqa: E402


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class QualitySnapshotDeletionResult11ContractTest(unittest.TestCase):
    def test_successor_schema_fixtures_negative_catalog_and_lock_pass(self) -> None:
        self.assertEqual([], checker.validate(PROJECT_ROOT))

    def test_task_zero_predecessor_schema_fixtures_ordering_and_lock_are_exact_bytes(self) -> None:
        self.assertEqual([], checker.predecessor_issues(PROJECT_ROOT))
        for relative, expected in checker.PREDECESSOR_RAW_DIGESTS.items():
            actual = "sha256:" + hashlib.sha256(
                (PROJECT_ROOT / relative).read_bytes()
            ).hexdigest()
            self.assertEqual(expected, actual, relative)

    def test_schema_keeps_v1_event_type_but_has_a_closed_1_1_data_discriminator(self) -> None:
        schema = load_json(PROJECT_ROOT / checker.SCHEMA)
        self.assertEqual([], schema_definition_issues(schema))
        self.assertEqual(
            "quality-snapshot-deletion-result-1.1.0.schema.json",
            schema["$id"],
        )
        self.assertEqual(checker.EVENT_TYPE, schema["properties"]["type"]["const"])
        self.assertEqual(
            {
                "specversion",
                "id",
                "source",
                "type",
                "subject",
                "time",
                "datacontenttype",
                "traceparent",
                "data",
            },
            set(schema["required"]),
        )
        self.assertEqual(set(schema["required"]), set(schema["properties"]))
        self.assertEqual(36, schema["$defs"]["uuidV7"]["minLength"])
        self.assertEqual(36, schema["$defs"]["uuidV7"]["maxLength"])
        self.assertEqual(71, schema["$defs"]["digest"]["minLength"])
        self.assertEqual(71, schema["$defs"]["digest"]["maxLength"])
        self.assertEqual(32, schema["$defs"]["traceId"]["minLength"])
        self.assertEqual(32, schema["$defs"]["traceId"]["maxLength"])
        self.assertEqual(55, schema["properties"]["traceparent"]["minLength"])
        self.assertEqual(55, schema["properties"]["traceparent"]["maxLength"])
        for name in ("completedData", "blockedData", "partialData", "failedData"):
            self.assertEqual(
                checker.RESULT_CONTRACT_VERSION,
                schema["$defs"][name]["properties"]["resultContractVersion"]["const"],
            )

    def test_available_authority_is_production_exact_and_unavailable_is_null_empty(self) -> None:
        schema = load_json(PROJECT_ROOT / checker.SCHEMA)
        registry = schema["$defs"]["consumerRegistryGuard"]
        available, unavailable = registry["oneOf"]
        self.assertNotIn("conformanceAnchorId", available["properties"])
        authority = schema["$defs"]["consumerRegistryAuthorityEvidence"]
        self.assertEqual(
            {
                "authorityEvidenceId",
                "provider",
                "evidenceRef",
                "scopeDigest",
                "registryVersion",
                "registryDigest",
                "membersDigest",
                "checkedAt",
                "verificationStatus",
                "runtimeEvidenceClaim",
            },
            set(authority["required"]),
        )
        self.assertFalse(authority["additionalProperties"])
        self.assertEqual(
            "consumer-registry-authority",
            authority["properties"]["provider"]["const"],
        )
        self.assertEqual(
            "verified", authority["properties"]["verificationStatus"]["const"]
        )
        self.assertEqual(
            "production-verified",
            authority["properties"]["runtimeEvidenceClaim"]["const"],
        )
        self.assertEqual(0, unavailable["properties"]["members"]["maxItems"])
        self.assertIsNone(
            unavailable["properties"]["authorityEvidence"]["const"]
        )
        self.assertIsNone(unavailable["properties"]["checkedAt"]["const"])

    def test_all_production_fixtures_are_strict_wire_valid_and_under_64_kib(self) -> None:
        expected = {
            "blocked": "blocked",
            "completed": "completed",
            "failed": "failed",
            "missing-authority-blocked": "blocked",
            "pre-due-blocked": "blocked",
            "partial": "partial",
        }
        schema = load_json(PROJECT_ROOT / checker.SCHEMA)
        for name, relative in checker.VALID_FIXTURES.items():
            with self.subTest(name=name):
                raw = (PROJECT_ROOT / relative).read_bytes()
                event = load_json(PROJECT_ROOT / relative)
                self.assertLessEqual(len(raw), checker.MAX_EVENT_BYTES)
                self.assertEqual([], schema_issues(event, schema))
                self.assertEqual([], checker.production_wire_issues(PROJECT_ROOT, raw))
                self.assertEqual(expected[name], event["data"]["result"])
                self.assertEqual("none", event["data"]["runtimeEvidenceClaim"])

    def test_pre_due_blocker_is_recomputed_from_trusted_time_not_caller_reason(self) -> None:
        event = self.load_fixture("pre-due-blocked")
        data = event["data"]
        self.assertEqual(["RETENTION_NOT_DUE"], data["blockerCodes"])
        self.assertEqual([], checker.production_event_issues(PROJECT_ROOT, event))

        missing_reason = copy.deepcopy(event)
        missing_reason["data"]["blockerCodes"] = []
        self.assert_reason(
            checker.production_event_issues(PROJECT_ROOT, missing_reason),
            "DELETION_RESULT_1_1_SCHEMA_REJECTED",
        )

        completed = self.load_fixture("completed")
        completed_data = completed["data"]
        completed_guards = completed_data["guards"]
        pre_due = event["data"]["guards"]["trustedTime"]["observedAt"]
        completed_guards["trustedTime"]["observedAt"] = pre_due
        completed_guards["legalHold"]["checkedAt"] = pre_due
        completed_guards["consumerRegistry"]["checkedAt"] = pre_due
        completed_guards["consumerRegistry"]["authorityEvidence"][
            "checkedAt"
        ] = pre_due
        completed_guards["consumerWatermarksCheckedAt"] = pre_due
        completed_guards["consumerWatermarks"][0]["attestation"][
            "attestedAt"
        ] = "2026-08-08T23:59:59.998Z"
        self.assert_reason(
            checker.production_event_issues(PROJECT_ROOT, completed),
            "DELETION_RESULT_1_1_CHRONOLOGY_INVALID",
        )

    def test_trailing_line_feeds_and_malformed_leaf_types_fail_closed(self) -> None:
        base = self.load_fixture("completed")
        mutations = []

        event_id = copy.deepcopy(base)
        event_id["id"] += "\n"
        event_id["data"]["eventId"] += "\n"
        mutations.append(event_id)

        traceparent = copy.deepcopy(base)
        traceparent["traceparent"] += "\n"
        mutations.append(traceparent)

        digest = copy.deepcopy(base)
        digest["data"]["scope"]["snapshotImmutableHash"] += "\n"
        mutations.append(digest)

        source = copy.deepcopy(base)
        source["data"]["scope"]["sourceId"] += "\n"
        mutations.append(source)

        consumer = copy.deepcopy(base)
        consumer["data"]["guards"]["consumerRegistry"]["members"][0][
            "consumerId"
        ] += "\n"
        mutations.append(consumer)

        malformed = copy.deepcopy(base)
        malformed["data"]["ownerLocalResults"]["readModels"][
            "transactionId"
        ] = []
        mutations.append(malformed)

        for mutation in mutations:
            with self.subTest(mutation=len(canonical_bytes(mutation))):
                self.assertIn(
                    "DELETION_RESULT_1_1_SCHEMA_REJECTED",
                    checker.production_event_issues(PROJECT_ROOT, mutation),
                )

    def test_members_and_registry_digests_are_independently_recomputed(self) -> None:
        completed = self.load_fixture("completed")
        registry = completed["data"]["guards"]["consumerRegistry"]
        members = registry["members"]
        authority = registry["authorityEvidence"]
        members_digest = self.digest(members)
        registry_digest = self.digest({
            "registryVersion": registry["registryVersion"],
            "members": members,
        })
        self.assertEqual(members_digest, authority["membersDigest"])
        self.assertEqual(registry_digest, registry["registryDigest"])
        self.assertEqual(registry_digest, authority["registryDigest"])
        self.assertEqual(
            registry["checkedAt"], authority["checkedAt"]
        )
        self.assertEqual(
            registry["checkedAt"],
            completed["data"]["guards"]["consumerWatermarksCheckedAt"],
        )

        missing = self.load_fixture("missing-authority-blocked")
        missing_registry = missing["data"]["guards"]["consumerRegistry"]
        self.assertEqual([], missing_registry["members"])
        self.assertIsNone(missing_registry["authorityEvidence"])
        self.assertEqual(
            self.digest({
                "registryVersion": checker.REGISTRY_VERSION,
                "members": [],
            }),
            missing_registry["registryDigest"],
        )

    def test_impact_scope_read_models_share_the_three_target_database_transaction(self) -> None:
        schema = load_json(PROJECT_ROOT / checker.SCHEMA)
        expected_refs = {
            "completedData": "#/$defs/completedDatabaseTarget",
            "partialData": "#/$defs/partialDatabaseTarget",
            "failedData": "#/$defs/failedDatabaseTarget",
        }
        owner_defs = {
            "completedData": "completedOwnerLocalResults",
            "partialData": "partialOwnerLocalResults",
            "failedData": "failedOwnerLocalResults",
        }
        for data_def, expected_ref in expected_refs.items():
            owner_ref = schema["$defs"][data_def]["properties"][
                "ownerLocalResults"
            ]["$ref"]
            self.assertEqual(f"#/$defs/{owner_defs[data_def]}", owner_ref)
            self.assertEqual(
                expected_ref,
                schema["$defs"][owner_defs[data_def]]["properties"][
                    "readModels"
                ]["$ref"],
            )

        for name in ("completed", "partial", "failed"):
            with self.subTest(name=name):
                data = self.load_fixture(name)["data"]
                targets = data["ownerLocalResults"]
                transaction_ids = {
                    targets[key]["transactionId"]
                    for key in checker.DATABASE_TARGET_KEYS
                }
                digests = {
                    targets[key]["transactionEvidenceDigest"]
                    for key in checker.DATABASE_TARGET_KEYS
                }
                self.assertEqual(1, len(transaction_ids))
                self.assertEqual(1, len(digests))
                self.assertEqual(
                    self.digest(
                        checker.database_transaction_evidence_material_1_1(
                            data, targets
                        )
                    ),
                    next(iter(digests)),
                )

    def test_every_declared_invalid_mutation_fails_for_its_fixed_reason(self) -> None:
        catalog = load_json(PROJECT_ROOT / checker.NEGATIVE_FIXTURES)
        cases = {case["caseId"]: case for case in catalog["cases"]}
        self.assertTrue(checker.MANDATORY_NEGATIVE_CASES.issubset(cases))
        self.assertEqual(len(cases), len(catalog["cases"]))
        for case_id, case in cases.items():
            with self.subTest(case=case_id):
                issues = checker.execute_negative_case(PROJECT_ROOT, case)
                self.assertTrue(
                    any(issue.startswith(case["expectedCode"]) for issue in issues),
                    f"{case_id}: expected {case['expectedCode']}, got {issues}",
                )

    def test_wire_accepts_pretty_json_but_rejects_bom_duplicate_unsafe_and_oversize(self) -> None:
        event = self.load_fixture("completed")
        pretty = json.dumps(event, ensure_ascii=False, indent=4).encode("utf-8")
        self.assertEqual(
            [], checker.production_wire_issues(PROJECT_ROOT, pretty)
        )
        compact = canonical_bytes(event)
        mutations = {
            "bom": b"\xef\xbb\xbf" + compact,
            "duplicate": b'{"id":"duplicate",' + compact[1:],
            "unsafe": compact.replace(
                b'"aggregateVersion":2',
                b'"aggregateVersion":9007199254740992',
                1,
            ),
            "oversize": compact + b" " * (
                checker.MAX_EVENT_BYTES - len(compact) + 1
            ),
        }
        for name, raw in mutations.items():
            with self.subTest(name=name):
                issues = checker.production_wire_issues(PROJECT_ROOT, raw)
                expected = (
                    "DELETION_RESULT_1_1_PAYLOAD_TOO_LARGE"
                    if name == "oversize"
                    else "DELETION_RESULT_1_1_WIRE_JSON_INVALID"
                )
                self.assertTrue(any(issue.startswith(expected) for issue in issues))

    def test_lineage_replay_idempotency_and_authority_identity_are_fail_closed(self) -> None:
        blocked = self.load_fixture("blocked")
        completed = self.load_fixture("completed")
        self.assertEqual(
            [], checker.production_lineage_issues([blocked, completed])
        )
        self.assertEqual(
            [], checker.production_lineage_issues([
                blocked, completed, copy.deepcopy(completed)
            ])
        )

        conflict = copy.deepcopy(completed)
        conflict["data"]["guards"]["consumerRegistry"]["authorityEvidence"][
            "evidenceRef"
        ] += "/conflict"
        self.assert_reason(
            checker.production_lineage_issues([blocked, completed, conflict]),
            "DELETION_RESULT_1_1_IDEMPOTENCY_CONFLICT",
        )

        gap = copy.deepcopy(completed)
        gap["data"]["aggregateVersion"] = 3
        self.assert_reason(
            checker.production_lineage_issues([blocked, gap]),
            "DELETION_RESULT_1_1_VERSION_GAP",
        )

        alias = copy.deepcopy(completed)
        prior_id = blocked["id"]
        authority = alias["data"]["guards"]["consumerRegistry"][
            "authorityEvidence"
        ]
        authority["authorityEvidenceId"] = prior_id
        authority["evidenceRef"] = (
            "consumer-registry-authority://production/quality-snapshot/"
            + prior_id
        )
        self.assert_reason(
            checker.production_lineage_issues([blocked, alias]),
            "DELETION_RESULT_1_1_LINEAGE_AUTHORITY_ID_ALIAS",
        )

        missing = self.load_fixture("missing-authority-blocked")
        future_alias = copy.deepcopy(completed)
        reserved_authority_id = missing["data"]["causationId"]
        future_alias["id"] = reserved_authority_id
        future_alias["data"]["eventId"] = reserved_authority_id
        self.assert_reason(
            checker.production_lineage_issues([missing, future_alias]),
            "DELETION_RESULT_1_1_LINEAGE_AUTHORITY_ID_ALIAS",
        )

    def test_delivery_decision_freezes_duplicate_conflict_old_apply_and_gap(self) -> None:
        cases = (
            (2, 2, True, "DUPLICATE"),
            (2, 2, False, "CONFLICT"),
            (2, 1, True, "OLD_IGNORED"),
            (2, 3, True, "APPLIED"),
            (2, 4, True, "GAP_BACKFILL_REQUIRED"),
        )
        for current, incoming, same_payload, expected in cases:
            with self.subTest(expected=expected):
                self.assertEqual(
                    expected,
                    checker.production_delivery_decision(
                        current, incoming, same_payload=same_payload
                    ),
                )

    def test_successor_lock_detects_raw_only_and_semantic_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            controlled = set(checker.PREDECESSOR_RAW_DIGESTS)
            controlled.update(checker.SUCCESSOR_LOCKED_FILES)
            controlled.add(str(checker.LOCK))
            for relative in controlled:
                source = PROJECT_ROOT / relative
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)

            schema_path = root / checker.SCHEMA
            schema_path.write_bytes(schema_path.read_bytes() + b"\n")
            self.assert_reason(
                checker.successor_lock_issues(root),
                "DELETION_RESULT_1_1_LOCK_RAW_MISMATCH",
            )

            shutil.copyfile(PROJECT_ROOT / checker.SCHEMA, schema_path)
            completed_path = root / checker.VALID_FIXTURES["completed"]
            completed = load_json(completed_path)
            completed["data"]["runtimeEvidenceClaim"] = "production-verified"
            completed_path.write_text(
                json.dumps(completed, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            lock_issues = checker.successor_lock_issues(root)
            self.assert_reason(
                lock_issues, "DELETION_RESULT_1_1_LOCK_RAW_MISMATCH"
            )
            self.assert_reason(
                lock_issues, "DELETION_RESULT_1_1_LOCK_CANONICAL_MISMATCH"
            )

    def test_predecessor_byte_drift_is_detected_even_if_successor_lock_is_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in checker.PREDECESSOR_RAW_DIGESTS:
                source = PROJECT_ROOT / relative
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
            predecessor = root / (
                "contracts/events/ingestion-quality/"
                "quality-snapshot-deletion-result.schema.json"
            )
            predecessor.write_bytes(predecessor.read_bytes() + b"\n")
            self.assert_reason(
                checker.predecessor_issues(root),
                "DELETION_RESULT_1_1_PREDECESSOR_RAW_DRIFT",
            )

    def load_fixture(self, name: str) -> dict:
        return load_json(PROJECT_ROOT / checker.VALID_FIXTURES[name])

    @staticmethod
    def digest(value: object) -> str:
        return "sha256:" + hashlib.sha256(canonical_bytes(value)).hexdigest()

    def assert_reason(self, issues: list[str], expected: str) -> None:
        self.assertTrue(
            any(issue.startswith(expected) for issue in issues),
            f"expected {expected}, got {issues}",
        )


if __name__ == "__main__":
    unittest.main()
