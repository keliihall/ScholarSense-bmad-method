package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Story25QualityRecoveryTaskJdbcBoundaryContractTest {
    private static final Path ADAPTER = Path.of(
            "src/main/java/cn/edu/suda/scholarsense/ingestionquality/adapters/outbound/"
                    + "JdbcQualityRecoveryTaskQueryStore.java");
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/ingestion-quality/"
                    + "V000016__ingestion-quality__quality_fuse_task_v1.sql");
    private static final Path ELIGIBILITY_ADAPTER = Path.of(
            "src/main/java/cn/edu/suda/scholarsense/ingestionquality/adapters/outbound/"
                    + "JdbcQualityEligibilityEventTransactionAdapter.java");
    private static final Path RETENTION_ADAPTER = Path.of(
            "src/main/java/cn/edu/suda/scholarsense/ingestionquality/adapters/outbound/"
                    + "JdbcCatalogRetentionCleanup.java");
    private static final Path RELAY_ADAPTER = Path.of(
            "src/main/java/cn/edu/suda/scholarsense/ingestionquality/adapters/outbound/"
                    + "JdbcQualityTaskRelayWork.java");

    @Test
    void pageHydrationHasAConstantThreeQueryUpperBound() throws Exception {
        String adapter = Files.readString(ADAPTER);

        assertEquals(3, occurrences(adapter, "jdbc.query("),
                "page size must not add JDBC round trips");
        assertTrue(adapter.contains("iq_find_quality_recovery_task_ids_v2("));
        assertTrue(adapter.contains("iq_find_quality_recovery_task_page_v2(?::uuid[])"));
        assertTrue(adapter.contains("iq_find_quality_recovery_task_page_rules(?::uuid[])"));
        assertFalse(adapter.contains("for (UUID"),
                "task hydration must remain bulk-shaped rather than N+1");
    }

    @Test
    void bulkQueriesAreBackedByOwnerSchemaIndexesAndDoNotCrossSchemas() throws Exception {
        String migration = Files.readString(MIGRATION);
        int queryFunctions = migration.indexOf(
                "create function ingestion_quality.iq_find_quality_recovery_task_ids(");
        int ownership = migration.indexOf(
                "alter table ingestion_quality.iq_quality_recovery_task_history owner to");
        String querySection = migration.substring(queryFunctions, ownership);

        assertTrue(migration.contains("create index iq_quality_recovery_task_current_page_idx"));
        assertTrue(migration.contains(
                "create index iq_quality_recovery_task_affected_rule_cover_idx"));
        assertFalse(querySection.matches("(?s).*\\b(?:identity|audit|catalog)\\..*"),
                "online projection functions must stay inside the owner schema");
    }

    @Test
    void currentEvidenceProjectionMatchesTheStrictPublicDto() throws Exception {
        String migration = Files.readString(MIGRATION);
        int start = migration.indexOf(
                "create function ingestion_quality.iq_find_quality_recovery_task_page(");
        int end = migration.indexOf(
                "create function ingestion_quality.iq_find_quality_recovery_task_page_rules(");
        String query = migration.substring(start, end);

        assertFalse(query.contains("history.current_evidence,history.aggregate_version"),
                "the public query must not expose internal digest evidence wholesale");
        assertTrue(query.contains("'qualityGateVersion',history.current_evidence->>'qualityGateVersion'"));
        assertTrue(query.contains("'qmdpVersion',history.current_evidence->>'qmdpVersion'"));
        assertTrue(query.contains("'qshmVersion',history.current_evidence->>'qshmVersion'"));
    }

    @Test
    void updatedTaskOrderingAndApiCursorUseTheSameOccurredAt() throws Exception {
        String migration = Files.readString(MIGRATION);

        assertTrue(migration.contains(
                "aggregate_version=excluded.aggregate_version,occurred_at=excluded.occurred_at,"));
        assertTrue(migration.contains(
                "history.aggregate_version,current_fact.occurred_at,"));
        assertTrue(migration.contains(
                "order by current_fact.occurred_at desc,current_fact.task_id desc"));
    }

    @Test
    void fuseAuthorizationUsesAuthorityEvidenceAndHasASeparateRejectionAudit() throws Exception {
        String adapter = Files.readString(ELIGIBILITY_ADAPTER);
        String migration = Files.readString(MIGRATION);

        assertFalse(adapter.contains("\"authorizationGeneration\", 1"));
        assertFalse(adapter.contains("\"serviceRef\", \"workload:"));
        assertTrue(adapter.contains("authorization.capture("));
        assertTrue(adapter.contains("authorization.revalidate("));
        assertTrue(adapter.contains("trustedTime.now()"));
        assertTrue(migration.contains(
                "create table ingestion_quality.iq_quality_fuse_rejection_audit"));
        assertTrue(migration.contains(
                "create function ingestion_quality.iq_append_quality_fuse_rejection_audit"));
    }

    @Test
    void fuseTaskPlanPersistsSelfContainedEligibilityMemberAndFormulaEvidence() throws Exception {
        String adapter = Files.readString(ELIGIBILITY_ADAPTER);
        String migration = Files.readString(MIGRATION);

        assertTrue(adapter.contains("\"eligibilityEvidence\""));
        assertTrue(adapter.contains("\"sourceContractVersion\""));
        assertTrue(adapter.contains("\"formulaEvidence\""));
        assertTrue(adapter.contains("\"comparisonResult\""));
        assertTrue(migration.contains("'eligibilities',iq_plan->'eligibilityEvidence'"));
        assertTrue(migration.contains("'formulaBoundaries',iq_plan->'formulaEvidence'"));
        assertTrue(migration.contains("iq_find_quality_snapshot_evidence_v2"));
    }

    @Test
    void retentionIsClosedP2yP90dAndLegalHoldAware() throws Exception {
        String migration = Files.readString(MIGRATION);

        assertTrue(migration.contains(
                "create function ingestion_quality.iq_cleanup_quality_fuse_expired("));
        assertTrue(migration.contains("interval '2 years'"));
        assertTrue(migration.contains("interval '90 days'"));
        assertTrue(migration.contains("not idempotency.legal_hold"));
        assertTrue(migration.contains("not read_audit.legal_hold"));
        assertTrue(migration.contains(
                "'scholarsense_ingestion_quality_retention_executor'"));
        assertFalse(migration.contains(
                "grant delete on ingestion_quality.iq_quality_fuse_episode_history"));
        String retentionAdapter = Files.readString(RETENTION_ADAPTER);
        assertTrue(retentionAdapter.contains("iq_cleanup_expired(?)"));
        assertTrue(retentionAdapter.contains("iq_cleanup_quality_fuse_expired(?)"));
    }

    @Test
    void relayHoldsTheTaskRoutePermitAcrossSendWithoutHoldingATransaction() throws Exception {
        String migration = Files.readString(MIGRATION);
        String relay = Files.readString(RELAY_ADAPTER);

        assertTrue(migration.contains("'quality-task-route:'||iq_task_id::text"));
        assertTrue(relay.contains("pg_catalog.pg_advisory_lock"));
        assertTrue(relay.contains("pg_catalog.pg_advisory_unlock"));
        assertFalse(relay.contains("setAutoCommit(false)"));
    }

    @Test
    void ownerScopeIsRequiredBeforeTheBoundedTaskIdSlice() throws Exception {
        String migration = Files.readString(MIGRATION);
        String service = Files.readString(Path.of(
                "src/main/java/cn/edu/suda/scholarsense/ingestionquality/application/"
                        + "QualityRecoveryTaskQueryService.java"));

        assertTrue(migration.contains("if requested_source_id is null"));
        assertTrue(migration.contains("where current_fact.source_id=requested_source_id"));
        assertFalse(migration.contains(
                "requested_source_id is null or current_fact.source_id=requested_source_id"));
        assertTrue(service.indexOf("authorizeOwnerScope(")
                < service.indexOf("List<QualityRecoveryTask> candidates = safeList(raw)"));
        assertFalse(service.contains("criteria.afterTaskId(), 101"));
    }

    private static int occurrences(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
