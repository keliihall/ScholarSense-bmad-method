package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class Story25cQualityFinalizationJdbcBoundaryContractTest {
    private static final Path MIGRATIONS = Path.of(
            "src/main/resources/db/migration");
    private static final Path V21 = MIGRATIONS.resolve(
            "ingestion-quality/V000021__ingestion-quality__quality_finalization_v1.sql");

    @Test
    void v21IsTheOnlyNextForwardMigrationAndPredecessorBytesStayFrozen() throws Exception {
        List<Integer> versions;
        try (var paths = Files.walk(MIGRATIONS)) {
            versions = paths.filter(path -> path.getFileName().toString().matches(
                            "V[0-9]{6}__.*\\.sql"))
                    .map(path -> Integer.parseInt(
                            path.getFileName().toString().substring(1, 7)))
                    .sorted(Comparator.naturalOrder()).toList();
        }
        assertEquals(21, versions.getLast());
        assertEquals(21, versions.stream().distinct().count());
        assertEquals(
                "21a66478ce29bf71838f4375c7162f5bbd390d5db60661981c5acfa03c419edb",
                sha256(MIGRATIONS.resolve(
                        "ingestion-quality/V000015__ingestion-quality__quality_eligibility_v1.sql")));
        assertEquals(
                "a720f004d30962b4b7bff2e8e734e1ee211f06866e8c3e02ec5c5d36b6eb791c",
                sha256(MIGRATIONS.resolve(
                        "ingestion-quality/V000016__ingestion-quality__quality_fuse_task_v1.sql")));
        assertEquals(
                "f24b6e516351eecdcdc036dc309afe57edc661b5b869b5186640c6ff29b4615e",
                sha256(MIGRATIONS.resolve(
                        "ingestion-quality/V000020__ingestion-quality__quality_recovery_v1.sql")));
    }

    @Test
    void observationWorkerHasDedicatedFactsLeaseFenceAndClosedRetention() throws Exception {
        String sql = Files.readString(V21);
        for (String required : List.of(
                "iq_recovery_observation_fact",
                "iq_recovery_observation_current",
                "iq_recovery_observation_decision",
                "iq_recovery_observation_job",
                "iq_recovery_observation_attempt",
                "iq_recovery_final_idempotency",
                "iq_recovery_final_execution_jti",
                "iq_recovery_window_outcome",
                "iq_claim_recovery_observation_job",
                "iq_is_recovery_observation_lease_current",
                "iq_finalize_recovery_observation_job",
                "iq_cleanup_quality_finalization_expired",
                "RECOVERY_VALIDATION_APPROVED")) {
            assertTrue(sql.contains(required), required);
        }
        assertTrue(sql.contains("lease_generation"));
        assertTrue(sql.contains("fencing"));
        assertTrue(sql.contains("interval '90 days'"));
        assertTrue(sql.contains("interval '2 years'"));
        assertTrue(sql.contains("legal_hold"));
        assertFalse(sql.contains("iq_mapping_recompute_job"));
    }

    @Test
    void observationPollingDoesNotConsumeTheTechnicalRetryBudget() throws Exception {
        String sql = Files.readString(V21);

        assertTrue(sql.contains("job.attempt_count<8"),
                "exhausted jobs must not remain in the claimable batch");
        assertTrue(sql.contains("attempt_count=case when requested_outcome='yielded'"),
                "normal NOT_READY polling must refund the claim-side retry increment");
        assertTrue(sql.contains("requested_outcome='retry-scheduled' and attempt_count>=8"),
                "the final technical failure must close the retry loop");
    }

    @Test
    void rawDmlGuardsRejectPartialTerminalStateReopenAndLateFence() throws Exception {
        String sql = Files.readString(V21);
        for (String required : List.of(
                "INGESTION_QUALITY_OBSERVATION_HISTORY_IMMUTABLE",
                "INGESTION_QUALITY_OBSERVATION_FENCE_STALE",
                "INGESTION_QUALITY_TASK_REOPEN_FORBIDDEN",
                "INGESTION_QUALITY_TERMINAL_STATE_PARTIAL",
                "INGESTION_QUALITY_MIXED_GENERATION",
                "status in ('open','closed')",
                "reason_code in",
                "RECOVERY_FINALIZED")) {
            assertTrue(sql.contains(required), required);
        }
    }

    @Test
    void observationFinalizerSharesTheSourceLockAndRejectsStaleReadyDecisions()
            throws Exception {
        String sql = Files.readString(V21);
        int start = sql.indexOf("create function ingestion_quality."
                + "iq_finalize_recovery_observation_job(");
        int end = sql.indexOf("create function ingestion_quality."
                + "iq_release_recovery_observation_job(", start);
        String function = sql.substring(start, end);

        assertTrue(function.contains("pg_advisory_xact_lock"));
        assertTrue(function.contains("iq_observation.status<>'observing'"));
        assertTrue(function.contains("fact_type='verified-quality-failure'"));
        assertTrue(function.contains("iq_build_quality_recovery_readiness_evidence"));
        assertTrue(function.contains("eligibility.status<>'recovering'"));
        assertTrue(function.contains("('ready','relapsed','policy-drift')"));
        assertTrue(function.contains("iq_policy_drift"));
    }

    @Test
    void finalizationTrustsOnlyIdentityOwnedApprovalAndLeaseEvidence() throws Exception {
        String sql = Files.readString(V21);

        assertTrue(sql.contains("identity_access.ia_verify_quality_finalization_approval"));
        assertTrue(sql.contains("identity_access.ia_verify_quality_finalization_lease"));
        assertTrue(sql.contains("owner to scholarsense_identity_online"));
        assertTrue(sql.contains("grant execute on function identity_access."
                + "ia_verify_quality_finalization_approval(jsonb)\n"
                + "to scholarsense_ingestion_quality_batch_owner"));
        assertTrue(sql.contains("grant execute on function identity_access."
                + "ia_verify_quality_finalization_lease(jsonb,timestamptz)\n"
                + "to scholarsense_ingestion_quality_batch_owner"));
        assertFalse(sql.contains("ia_verify_quality_finalization_approval(jsonb)\n"
                + "to scholarsense_ingestion_quality_online"));

        String bind = function(sql,
                "create function ingestion_quality.iq_bind_quality_finalization_approval(",
                "create function ingestion_quality.iq_find_quality_finalization_replay(");
        String execute = function(sql,
                "create function ingestion_quality.iq_execute_quality_finalization(",
                "create function ingestion_quality.iq_cleanup_quality_finalization_expired(");
        assertTrue(bind.contains("ia_verify_quality_finalization_approval("));
        assertTrue(execute.contains("ia_verify_quality_finalization_lease("));
    }

    @Test
    void rejectedOrCancelledApprovalCanOnlyRestartWithFreshPendingEvidence()
            throws Exception {
        String sql = Files.readString(V21);
        String bind = function(sql,
                "create function ingestion_quality.iq_bind_quality_finalization_approval(",
                "create function ingestion_quality.iq_find_quality_finalization_replay(");

        assertTrue(bind.contains(
                "iq_observation.finalization_state in ('approval-rejected','cancelled')"));
        assertTrue(bind.contains("and iq_state='approval-pending'"));
        assertTrue(sql.contains("'approval-approved','approval-rejected','cancelled',\n"
                + "            'finalizing','executed'"));
    }

    @Test
    void terminalObservationDoesNotBlockANewRecoveryInTheSameGeneration()
            throws Exception {
        String sql = Files.readString(V21);

        assertFalse(sql.contains("unique (source_id,dependency_id,generation)"));
        assertTrue(sql.contains("create unique index "
                + "iq_recovery_observation_active_source_generation_uk"));
        assertTrue(sql.contains("where status in ('observing','ready')"));
        assertTrue(sql.contains("and observation.status in ('observing','ready')"));
        String query = function(sql,
                "create function ingestion_quality.iq_load_recovery_observation_view(",
                "create function ingestion_quality.iq_load_quality_finalization_context(");
        assertTrue(query.contains(
                "order by observation.updated_at desc,observation.recovery_id desc limit 1"));
    }

    @Test
    void retentionKeepsTheDecisionReferencedByAnActiveReadyObservation()
            throws Exception {
        String sql = Files.readString(V21);
        String cleanup = function(sql,
                "create function ingestion_quality.iq_cleanup_quality_finalization_expired(",
                "-- Cross-table terminal validation");

        assertTrue(cleanup.contains(
                "current_decision.recovery_id=decision.recovery_id"));
        assertTrue(cleanup.contains("current_decision.status='ready'"));
    }

    @Test
    void observationClaimReturnsOnlyTheBoundedPolicyRelevantFactTail()
            throws Exception {
        String sql = Files.readString(V21);
        String claim = function(sql,
                "create function ingestion_quality.iq_claim_recovery_observation_job(",
                "create function ingestion_quality.iq_is_recovery_observation_lease_current(");

        assertTrue(claim.contains("fact.fact_type='published-pair'"));
        assertTrue(claim.contains("limit 3"));
        assertTrue(claim.contains("fact.fact_type='verified-quality-failure'"));
        assertTrue(claim.contains("limit 1"));
        assertTrue(claim.contains("policy_fact"));
    }

    @Test
    void ownerDerivesAUniqueDecisionIdForEveryAggregateVersion() throws Exception {
        String sql = Files.readString(V21);
        String finalize = function(sql,
                "create function ingestion_quality.iq_finalize_recovery_observation_job(",
                "create function ingestion_quality.iq_release_recovery_observation_job(");

        assertTrue(finalize.contains("iq_quality_uuid_v7_derive("));
        assertTrue(finalize.contains("'recovery-observation-decision:'||"));
        assertTrue(finalize.contains("(iq_observation.aggregate_version+1)"));
        assertFalse(finalize.contains("(requested_decision->>'decisionId')::uuid"));
    }

    private static String function(String sql, String startMarker, String endMarker) {
        int start = sql.indexOf(startMarker);
        int end = sql.indexOf(endMarker, start);
        assertTrue(start >= 0, startMarker);
        assertTrue(end > start, endMarker);
        return sql.substring(start, end);
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
