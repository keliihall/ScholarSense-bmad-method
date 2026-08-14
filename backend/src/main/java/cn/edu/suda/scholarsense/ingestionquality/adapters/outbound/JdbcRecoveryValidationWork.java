package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryValidationCandidate;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryValidationWorkPort;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationCheckpoint;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationClaim;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationErrorCode;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationPhase;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationResult;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Durable recovery worker adapter; every mutating function is lease-generation fenced. */
public final class JdbcRecoveryValidationWork implements RecoveryValidationWorkPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcRecoveryValidationWork(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public List<RecoveryValidationCandidate> findClaimable(int limit, Instant trustedNow) {
        return jdbc.query("""
                select * from ingestion_quality.iq_find_claimable_recovery_validation_jobs(?,?)
                """, (row, ignored) -> new RecoveryValidationCandidate(
                row.getObject("job_id", UUID.class), 0), limit, Timestamp.from(trustedNow));
    }

    @Override
    public RecoveryValidationClaim claim(
            UUID jobId, String workerDigest, Instant trustedNow, Duration leaseDuration) {
        long seconds = leaseDuration.toSeconds();
        if (seconds < 1 || seconds > 300 || !leaseDuration.equals(Duration.ofSeconds(seconds))) {
            throw invalid();
        }
        JsonNode root = callJson("""
                select ingestion_quality.iq_claim_recovery_validation_job(?,?,?,?)::text
                """, jobId, workerDigest, Timestamp.from(trustedNow), Math.toIntExact(seconds));
        long checkpointVersion = number(root, "checkpointVersion");
        JsonNode checkpoint = root.get("checkpoint");
        return new RecoveryValidationClaim(
                uuid(root, "jobId"), Math.toIntExact(number(root, "attemptNumber")),
                number(root, "leaseGeneration"), text(root, "leaseOwnerDigest"),
                instant(root, "claimedAt"), instant(root, "leaseExpiresAt"),
                checkpointVersion, checkpointVersion == 0 ? null : checkpoint(checkpoint));
    }

    @Override
    public boolean isLeaseCurrent(UUID jobId, long generation, Instant trustedNow) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select ingestion_quality.iq_is_recovery_validation_lease_current(?,?,?)
                """, Boolean.class, jobId, generation, Timestamp.from(trustedNow)));
    }

    @Override
    public boolean checkpoint(
            UUID jobId, long generation, long expectedCheckpointVersion,
            RecoveryValidationCheckpoint checkpoint, Instant trustedNow) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select ingestion_quality.iq_checkpoint_recovery_validation_job(
                    ?,?,?,?::jsonb,?)
                """, Boolean.class, jobId, generation, expectedCheckpointVersion,
                encode(checkpointMap(checkpoint)), Timestamp.from(trustedNow)));
    }

    @Override
    public boolean complete(
            UUID jobId, long generation, RecoveryValidationResult result, Instant trustedNow) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select ingestion_quality.iq_finalize_recovery_validation_job(
                    ?,?,?::jsonb,?)
                """, Boolean.class, jobId, generation,
                encode(resultMap(result)), Timestamp.from(trustedNow)));
    }

    @Override
    public boolean fail(
            UUID jobId, long generation, RecoveryValidationErrorCode error, Instant trustedNow) {
        return release(jobId, generation, "fail", error, null, trustedNow);
    }

    @Override
    public boolean retry(
            UUID jobId, long generation, RecoveryValidationErrorCode error,
            Instant nextAttemptAt, Instant trustedNow) {
        return release(jobId, generation, "retry", error, nextAttemptAt, trustedNow);
    }

    @Override
    public boolean yield(
            UUID jobId, long generation, Instant nextAttemptAt, Instant trustedNow) {
        return release(jobId, generation, "yield", null, nextAttemptAt, trustedNow);
    }

    @Override
    public boolean cancel(UUID jobId, long generation, Instant trustedNow) {
        return release(jobId, generation, "cancel", null, null, trustedNow);
    }

    private boolean release(
            UUID jobId, long generation, String operation, RecoveryValidationErrorCode error,
            Instant nextAttemptAt, Instant trustedNow) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select ingestion_quality.iq_release_recovery_validation_job(?,?,?,?,?,?)
                """, Boolean.class, jobId, generation, operation,
                error == null ? null : error.name(),
                nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt),
                Timestamp.from(trustedNow)));
    }

    private Map<String, Object> checkpointMap(RecoveryValidationCheckpoint value) {
        return Map.of(
                "checkpointVersion", value.checkpointVersion(),
                "phase", wire(value.phase()),
                "phaseCompleted", value.phaseCompleted(),
                "opaqueResumeRef", value.opaqueResumeRef(),
                "cursorDigest", value.cursorDigest(),
                "partialSummaryDigest", value.partialSummaryDigest(),
                "processedCount", value.processedCount(),
                "mismatchCount", value.mismatchCount());
    }

    private Map<String, Object> resultMap(RecoveryValidationResult value) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", value.schemaVersion());
        out.put("resultId", value.resultId().toString());
        out.put("jobId", value.jobId().toString());
        out.put("jobVersion", value.jobVersion());
        out.put("recoveryRequestId", value.recoveryRequestId().toString());
        out.put("episodeId", value.episodeId().toString());
        out.put("taskId", value.taskId().toString());
        out.put("state", value.state().name().toLowerCase());
        out.put("inputDigest", value.inputDigest());
        out.put("qualityRecoveryPolicyVersion", value.qualityRecoveryPolicyVersion());
        out.put("qualityRecoveryPolicyDigest", value.qualityRecoveryPolicyDigest());
        out.put("backfillStartWatermarkDigest", value.backfillStartWatermarkDigest());
        out.put("backfillTargetWatermarkDigest", value.backfillTargetWatermarkDigest());
        out.put("backfillSummaryDigest", value.backfillSummaryDigest());
        out.put("reconciliationExpectedCount", value.reconciliationExpectedCount());
        out.put("reconciliationActualCount", value.reconciliationActualCount());
        out.put("reconciliationMismatchCount", value.reconciliationMismatchCount());
        out.put("reconciliationSummaryDigest", value.reconciliationSummaryDigest());
        out.put("sampleSummaryVersion", value.sampleSummaryVersion());
        out.put("sampleSummaryDigest", value.sampleSummaryDigest());
        out.put("populationCount", value.populationCount());
        out.put("selectedCount", value.selectedCount());
        out.put("strata", value.strata().stream().map(stratum -> Map.of(
                "stratumCode", stratum.stratumCode(),
                "populationCount", stratum.populationCount(),
                "selectedCount", stratum.selectedCount(),
                "mismatchCount", stratum.mismatchCount(),
                "summaryDigest", stratum.summaryDigest())).toList());
        out.put("strataSummaryDigest", value.strataSummaryDigest());
        out.put("expectedDigest", value.expectedDigest());
        out.put("actualDigest", value.actualDigest());
        out.put("mismatchCount", value.mismatchCount());
        out.put("readinessEvidence", readinessMap(value.readinessEvidence()));
        out.put("qualified", value.qualified());
        out.put("errorCode", value.errorCode() == null ? null : value.errorCode().name());
        out.put("completedAt", value.completedAt().toString());
        out.put("resultDigest", value.resultDigest());
        out.put("traceId", value.traceId());
        out.put("targetState", "recovering");
        out.put("transitionApplied", false);
        out.put("canonicalizationProfile", "SCHOLARSENSE-CANONICAL-JSON-1.0.0");
        out.put("runtimeEvidenceClaim", "installed-and-verified");
        return out;
    }

    private static Map<String, Object> readinessMap(
            cn.edu.suda.scholarsense.ingestionquality.domain
                    .RecoveryValidationReadinessEvidence value) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("episodeGeneration", value.episodeGeneration());
        out.put("expectedEpisodeVersion", value.expectedEpisodeVersion());
        out.put("expectedTaskVersion", value.expectedTaskVersion());
        out.put("sourceId", value.sourceId());
        out.put("dependencyId", value.dependencyId());
        out.put("ruleVersionsDigest", value.ruleVersionsDigest());
        out.put("memberSetDigest", value.memberSetDigest());
        out.put("watermarksDigest", value.watermarksDigest());
        out.put("sourceClass", value.sourceClass());
        out.put("sourceSchemaVersion", value.sourceSchemaVersion());
        out.put("sourceSchemaDigest", value.sourceSchemaDigest());
        out.put("dependencyVersion", value.dependencyVersion());
        out.put("dependencyDigest", value.dependencyDigest());
        out.put("eligibilityBindings", value.eligibilityBindings().stream().map(binding -> Map.of(
                "eligibilityId", binding.eligibilityId().toString(),
                "ruleId", binding.ruleId(), "ruleVersion", binding.ruleVersion(),
                "expectedAggregateVersion", binding.expectedAggregateVersion())).toList());
        out.put("currentBindingDigest", value.currentBindingDigest());
        var quality = value.qualityEvidence();
        out.put("qualityEvidence", Map.of(
                "snapshotIdsDigest", quality.snapshotIdsDigest(),
                "snapshotHashesDigest", quality.snapshotHashesDigest(),
                "metricResultsDigest", quality.metricResultsDigest(),
                "allRequiredMetricsPassed", quality.allRequiredMetricsPassed(),
                "sourceWatermark", quality.sourceWatermark(),
                "dependencyWatermark", quality.dependencyWatermark()));
        var batch = value.batchEvidence();
        out.put("batchEvidence", Map.of(
                "sequenceEvidenceVersion", batch.sequenceEvidenceVersion(),
                "sequenceEvidenceDigest", batch.sequenceEvidenceDigest(),
                "requiredConsecutivePassedBatches", batch.requiredConsecutivePassedBatches(),
                "actualConsecutivePassedBatches", batch.actualConsecutivePassedBatches(),
                "qualified", batch.qualified()));
        var backfill = value.backfillEvidence();
        out.put("backfillEvidence", Map.of(
                "lastKnownGoodWatermark", backfill.lastKnownGoodWatermark(),
                "trustedNow", backfill.trustedNow().toString(),
                "lookbackDays", backfill.lookbackDays(),
                "backfillStartWatermark", backfill.backfillStartWatermark(),
                "backfillCompletedWatermark", backfill.backfillCompletedWatermark(),
                "evidenceDigest", backfill.evidenceDigest(), "status", backfill.status()));
        out.put("requiredMembersEligible", value.requiredMembersEligible());
        out.put("missingEvidenceCodes", value.missingEvidenceCodes().stream()
                .map(Enum::name).toList());
        out.put("impactAlreadyExpiredCount", value.impactAlreadyExpiredCount());
        out.put("impactPotentiallyActionableCount", value.impactPotentiallyActionableCount());
        out.put("impactExpectedToExpireCount", value.impactExpectedToExpireCount());
        out.put("trustedStartedAt", value.trustedStartedAt().toString());
        out.put("evidenceDigest", value.evidenceDigest());
        return out;
    }

    private RecoveryValidationCheckpoint checkpoint(JsonNode value) {
        if (value == null || !value.isObject()) throw invalid();
        return new RecoveryValidationCheckpoint(
                number(value, "checkpointVersion"), phase(text(value, "phase")),
                bool(value, "phaseCompleted"), text(value, "opaqueResumeRef"),
                text(value, "cursorDigest"), text(value, "partialSummaryDigest"),
                number(value, "processedCount"), number(value, "mismatchCount"));
    }

    private JsonNode callJson(String sql, Object... args) {
        String value = jdbc.queryForObject(sql, String.class, args);
        try {
            JsonNode root = json.readTree(Objects.requireNonNull(value));
            if (!root.isObject()) throw invalid();
            return root;
        } catch (JacksonException malformed) {
            throw new IllegalStateException(
                    "INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID", malformed);
        }
    }

    private String encode(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException malformed) {
            throw new IllegalStateException(
                    "INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID", malformed);
        }
    }

    private static String wire(RecoveryValidationPhase value) {
        return switch (value) {
            case BACKFILL -> "backfill";
            case FULL_RECONCILIATION -> "full-reconciliation";
            case SAMPLE_RECOMPUTE -> "sample-recompute";
        };
    }

    private static RecoveryValidationPhase phase(String value) {
        return switch (value) {
            case "backfill" -> RecoveryValidationPhase.BACKFILL;
            case "full-reconciliation" -> RecoveryValidationPhase.FULL_RECONCILIATION;
            case "sample-recompute" -> RecoveryValidationPhase.SAMPLE_RECOMPUTE;
            default -> throw invalid();
        };
    }

    private static String text(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid();
        return value.asText();
    }

    private static long number(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isIntegralNumber()) throw invalid();
        return value.asLong();
    }

    private static boolean bool(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isBoolean()) throw invalid();
        return value.asBoolean();
    }

    private static UUID uuid(JsonNode root, String name) {
        try {
            UUID value = UUID.fromString(text(root, name));
            if (value.version() != 7 || value.variant() != 2) throw invalid();
            return value;
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
    }

    private static Instant instant(JsonNode root, String name) {
        try {
            Instant value = Instant.parse(text(root, name));
            if (value.getNano() % 1_000 != 0) throw invalid();
            return value;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID");
    }
}
