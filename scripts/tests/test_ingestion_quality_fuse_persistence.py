import shutil
import tempfile
import unittest
from pathlib import Path

from scripts import check_ingestion_quality_fuse_persistence as checker


ROOT = Path(__file__).resolve().parents[2]


class IngestionQualityFusePersistenceTest(unittest.TestCase):
    def test_repository_persistence_successor_is_valid(self) -> None:
        self.assertEqual([], checker.validate(ROOT))

    def test_missing_atomic_task_outbox_write_is_rejected(self) -> None:
        with self.copy() as root:
            migration = root / checker.MIGRATION
            body = migration.read_text(encoding="utf-8").replace(
                "insert into ingestion_quality.iq_quality_task_outbox", "-- removed task outbox")
            migration.write_text(body, encoding="utf-8")
            self.assertTrue(any("task outbox" in issue for issue in checker.validate(root)))

    def test_lock_order_and_unique_active_episode_cannot_drift(self) -> None:
        with self.copy() as root:
            migration = root / checker.MIGRATION
            body = migration.read_text(encoding="utf-8")
            body = body.replace("quality-fuse-episode:", "episode-lock-removed:")
            body = body.replace("where active", "where false")
            migration.write_text(body, encoding="utf-8")
            issues = checker.validate(root)
            self.assertTrue(any("stable lock order" in issue for issue in issues))
            self.assertTrue(any("unique active episode" in issue for issue in issues))

    def test_predecessor_v15_mutation_is_rejected(self) -> None:
        with self.copy() as root:
            predecessor = root / checker.V15
            predecessor.write_text(
                predecessor.read_text(encoding="utf-8") + "\n-- mutation\n",
                encoding="utf-8")
            self.assertTrue(any("V15 predecessor" in issue for issue in checker.validate(root)))

    def test_delivery_cannot_update_quality_or_task_business_state(self) -> None:
        with self.copy() as root:
            migration = root / checker.MIGRATION
            body = migration.read_text(encoding="utf-8")
            marker = "-- DELIVERY_STATE_ORTHOGONALITY"
            body = body.replace(marker, "update ingestion_quality.iq_quality_recovery_task_current set status='closed';")
            migration.write_text(body, encoding="utf-8")
            self.assertTrue(any("delivery orthogonality" in issue for issue in checker.validate(root)))

    def test_task_relay_route_fence_cannot_be_removed(self) -> None:
        with self.copy() as root:
            migration = root / checker.MIGRATION
            body = migration.read_text(encoding="utf-8").replace(
                "task.aggregate_version=queued.route_sequence",
                "task.aggregate_version>0",
            )
            migration.write_text(body, encoding="utf-8")
            self.assertTrue(any(
                "claim, send and finalizers" in issue for issue in checker.validate(root)
            ))

    def test_route_send_permit_cannot_be_removed(self) -> None:
        with self.copy() as root:
            relay = root / checker.RELAY_WORK
            relay.write_text(
                relay.read_text(encoding="utf-8").replace(
                    "pg_catalog.pg_advisory_lock", "pg_catalog.pg_route_lock_removed"
                ),
                encoding="utf-8",
            )
            self.assertTrue(any(
                "non-transactional route permit" in issue for issue in checker.validate(root)
            ))

    def test_production_retention_execution_cannot_omit_fuse_cleanup(self) -> None:
        with self.copy() as root:
            cleanup = root / checker.RETENTION_CLEANUP
            cleanup.write_text(
                cleanup.read_text(encoding="utf-8").replace(
                    "iq_cleanup_quality_fuse_expired", "iq_cleanup_quality_fuse_expired_removed"
                ),
                encoding="utf-8",
            )
            self.assertTrue(any(
                "production retention scheduler" in issue for issue in checker.validate(root)
            ))

    def test_outbox_explicitly_maps_internal_task_shapes_to_public_v1(self) -> None:
        with self.copy() as root:
            migration = root / checker.MIGRATION
            body = migration.read_text(encoding="utf-8")
            body = body.replace(
                "ingestion_quality.iq_quality_public_task_reason(iq_trigger_reason)",
                "iq_trigger_reason",
            ).replace(
                "ingestion_quality.iq_quality_public_affected_rules(iq_plan->'affectedRules')",
                "iq_plan->'affectedRules'",
            )
            migration.write_text(body, encoding="utf-8")
            issues = checker.validate(root)
            self.assertTrue(any("public task reason mapping" in issue for issue in issues))
            self.assertTrue(any("public affectedRules mapping" in issue for issue in issues))

    def test_bootstrap_transition_cannot_choose_episode_reason(self) -> None:
        with self.copy() as root:
            migration = root / checker.MIGRATION
            body = migration.read_text(encoding="utf-8").replace(
                "transition->>'reasonCode'=transition->>'evaluatedReasonCode'",
                "true",
            )
            migration.write_text(body, encoding="utf-8")
            self.assertTrue(any(
                "true triggering transition" in issue for issue in checker.validate(root)
            ))

    def test_work_item_hmac_key_version_is_persisted_with_the_episode(self) -> None:
        with self.copy() as root:
            migration = root / checker.MIGRATION
            body = migration.read_text(encoding="utf-8").replace(
                "work_item_key_version varchar(32) not null",
                "work_item_key_version_removed varchar(32) not null",
            )
            migration.write_text(body, encoding="utf-8")
            self.assertTrue(any(
                "work-item HMAC key version" in issue for issue in checker.validate(root)
            ))

    def test_authorization_is_authoritative_and_rejections_are_audited(self) -> None:
        with self.copy() as root:
            adapter = root / checker.ADAPTER
            body = adapter.read_text(encoding="utf-8")
            body = body.replace("authorization.capture(", "selfReportedAuthorization(")
            body = body.replace(
                "iq_append_quality_fuse_rejection_audit",
                "iq_removed_quality_fuse_rejection_audit",
            )
            adapter.write_text(body, encoding="utf-8")
            issues = checker.validate(root)
            self.assertTrue(any("authoritative authorization" in issue for issue in issues))
            self.assertTrue(any("minimal audit fact" in issue for issue in issues))

    def test_rejection_audit_is_append_only(self) -> None:
        with self.copy() as root:
            migration = root / checker.MIGRATION
            body = migration.read_text(encoding="utf-8").replace(
                "before update or delete on ingestion_quality.iq_quality_fuse_rejection_audit",
                "before update on ingestion_quality.iq_quality_fuse_rejection_audit",
            )
            migration.write_text(body, encoding="utf-8")
            self.assertTrue(any(
                "rejection audit must be append-only" in issue
                for issue in checker.validate(root)
            ))

    def test_complete_handoff_evidence_cannot_be_dropped(self) -> None:
        with self.copy() as root:
            adapter = root / checker.ADAPTER
            body = adapter.read_text(encoding="utf-8").replace(
                "eligibilityEvidence", "handoffRemoved"
            )
            adapter.write_text(body, encoding="utf-8")
            self.assertTrue(any(
                "eligibility, member and formula evidence" in issue
                for issue in checker.validate(root)
            ))

    def test_retention_cannot_ignore_legal_hold(self) -> None:
        with self.copy() as root:
            migration = root / checker.MIGRATION
            body = migration.read_text(encoding="utf-8").replace(
                "not idempotency.legal_hold", "true"
            )
            migration.write_text(body, encoding="utf-8")
            self.assertTrue(any(
                "retention must be legal-hold-aware" in issue
                for issue in checker.validate(root)
            ))

    def test_page_slice_cannot_precede_owner_authorization(self) -> None:
        with self.copy() as root:
            service = root / checker.QUERY_SERVICE
            body = service.read_text(encoding="utf-8").replace(
                "criteria.afterTaskId(), criteria.limit()",
                "criteria.afterTaskId(), 101",
            )
            service.write_text(body, encoding="utf-8")
            self.assertTrue(any(
                "authorized source owner" in issue for issue in checker.validate(root)
            ))

    def copy(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        for relative in checker.COPY_PATHS:
            source = ROOT / relative
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)

        class Copied:
            def __enter__(self):
                return root

            def __exit__(self, *_):
                temporary.cleanup()

        return Copied()


if __name__ == "__main__":
    unittest.main()
