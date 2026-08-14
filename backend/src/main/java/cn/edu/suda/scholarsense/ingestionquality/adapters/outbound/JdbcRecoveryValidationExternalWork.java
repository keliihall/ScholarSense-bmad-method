package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.RecoverySampleRecomputeCommand;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoverySampleRecomputeOutcome;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoverySampleRecomputePort;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryValidationExecution;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryBackfillPort;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryBackfillRequest;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryBackfillResult;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryFullReconciliationPort;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryFullReconciliationRequest;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryFullReconciliationResult;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryValidationDependencyAvailability;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryValidationExecutionRequest;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryValidationExternalWorkPort;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationCheckpoint;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationErrorCode;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationJobStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationPhase;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationReadinessEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoveryPolicy;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityRecoverySourceClassRegistry;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleNormalizedInput;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleNormalizedInputPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Executes one bounded validation phase per invocation, outside the owner transaction. */
public final class JdbcRecoveryValidationExternalWork
        implements RecoveryValidationExternalWorkPort {
    private static final String PROVIDER_VERSION = "RECOVERY-SAMPLE-PROVIDER-1.0.0";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RecoverySampleNormalizedInputPort sampleInputs;
    private final RecoverySampleRecomputePort samples;
    private final RecoveryBackfillPort backfills;
    private final RecoveryFullReconciliationPort reconciliations;
    private final QualityRecoveryPolicy policy;
    private final QualityRecoverySourceClassRegistry sourceClasses;
    private final Supplier<Instant> time;
    private final Supplier<UUID> ids;

    public JdbcRecoveryValidationExternalWork(
            JdbcTemplate jdbc, ObjectMapper json,
            RecoverySampleNormalizedInputPort sampleInputs,
            RecoverySampleRecomputePort samples,
            RecoveryBackfillPort backfills,
            RecoveryFullReconciliationPort reconciliations,
            QualityRecoveryPolicy policy,
            QualityRecoverySourceClassRegistry sourceClasses,
            Supplier<Instant> time, Supplier<UUID> ids) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
        this.sampleInputs = Objects.requireNonNull(sampleInputs);
        this.samples = Objects.requireNonNull(samples);
        this.backfills = Objects.requireNonNull(backfills);
        this.reconciliations = Objects.requireNonNull(reconciliations);
        this.policy = Objects.requireNonNull(policy);
        this.sourceClasses = Objects.requireNonNull(sourceClasses);
        this.time = Objects.requireNonNull(time);
        this.ids = Objects.requireNonNull(ids);
    }

    @Override
    public RecoveryValidationExecution execute(RecoveryValidationExecutionRequest request) {
        JsonNode context = context(request.jobId());
        long nextVersion = request.checkpointVersion() + 1;
        if (request.checkpoint() == null) {
            RecoveryBackfillResult backfill = backfill(context);
            if (backfill.availability()
                    != RecoveryValidationDependencyAvailability.AVAILABLE) {
                return dependencyFailure(backfill.retryable());
            }
            return RecoveryValidationExecution.checkpoint(new RecoveryValidationCheckpoint(
                    nextVersion, RecoveryValidationPhase.BACKFILL, true,
                    resume(context, "full-reconciliation"),
                    backfill.targetWatermarkDigest(), backfill.summaryDigest(),
                    backfill.processedCount(), 0));
        }
        if (request.checkpoint().phase() == RecoveryValidationPhase.BACKFILL) {
            RecoveryFullReconciliationResult reconciliation = reconciliation(
                    context, request.checkpoint().partialSummaryDigest());
            if (reconciliation.availability()
                    != RecoveryValidationDependencyAvailability.AVAILABLE) {
                return dependencyFailure(reconciliation.retryable());
            }
            return RecoveryValidationExecution.checkpoint(new RecoveryValidationCheckpoint(
                    nextVersion, RecoveryValidationPhase.FULL_RECONCILIATION, true,
                    resume(context, "sample-recompute"),
                    reconciliation.summaryDigest(), reconciliation.summaryDigest(),
                    reconciliation.actualCount(), reconciliation.mismatchCount()));
        }
        if (request.checkpoint().phase() != RecoveryValidationPhase.FULL_RECONCILIATION) {
            return RecoveryValidationExecution.failed(
                    RecoveryValidationErrorCode.RETRY_EXHAUSTED);
        }
        Instant now = trustedNow();
        JsonNode binding = context.required("binding");
        RecoveryBackfillResult backfill = backfill(context);
        if (backfill.availability()
                != RecoveryValidationDependencyAvailability.AVAILABLE) {
            return dependencyFailure(backfill.retryable());
        }
        RecoveryFullReconciliationResult reconciliation = reconciliation(
                context, backfill.summaryDigest());
        if (reconciliation.availability()
                != RecoveryValidationDependencyAvailability.AVAILABLE) {
            return dependencyFailure(reconciliation.retryable());
        }
        List<RecoverySampleNormalizedInput.Stratum> strata = strata(context.required("strata"));
        String strataDigest = digest(canonicalStrata(strata));
        String selectionRef = text(context, "opaqueSubjectWindowSelectionRef");
        sampleInputs.stage(new RecoverySampleNormalizedInput(
                selectionRef, 1, text(binding, "ruleVersionsDigest"),
                text(binding, "memberSetDigest"), text(binding, "watermarksDigest"),
                text(binding, "qualityRecoveryPolicyDigest"), text(binding, "selectionSeed"),
                strata, strataDigest, now, now, now.plusSeconds(900)));
        String sampleRequestDigest = sampleRequestDigest(context, binding, selectionRef);
        RecoverySampleRecomputeOutcome outcome = samples.recompute(
                new RecoverySampleRecomputeCommand(
                        PROVIDER_VERSION, text(context, "recoveryRequestId"),
                        text(context, "episodeId"), text(context, "taskId"),
                        text(binding, "ruleVersionsDigest"), text(binding, "memberSetDigest"),
                        text(binding, "watermarksDigest"),
                        "QRP-1.0.0", text(binding, "qualityRecoveryPolicyDigest"),
                        selectionRef, text(binding, "selectionSeed"), 100,
                        sampleRequestDigest, text(binding, "traceId")));
        if (outcome.availability() != RecoverySampleRecomputeOutcome.Availability.AVAILABLE) {
            return RecoveryValidationExecution.failed(
                    outcome.retryable() ? RecoveryValidationErrorCode.DEPENDENCY_UNAVAILABLE
                            : RecoveryValidationErrorCode.PROVIDER_NOT_INSTALLED);
        }
        return RecoveryValidationExecution.succeeded(
                result(context, binding, backfill, reconciliation, outcome.result()));
    }

    private RecoveryBackfillResult backfill(JsonNode context) {
        JsonNode binding = context.required("binding");
        return backfills.execute(new RecoveryBackfillRequest(
                text(context, "recoveryRequestId"), text(context, "episodeId"),
                text(context, "taskId"), text(binding, "ruleVersionsDigest"),
                text(binding, "memberSetDigest"),
                text(context, "backfillStartWatermarkDigest"),
                text(context, "backfillTargetWatermarkDigest"),
                text(binding, "traceId")));
    }

    private RecoveryFullReconciliationResult reconciliation(
            JsonNode context, String backfillSummaryDigest) {
        JsonNode binding = context.required("binding");
        return reconciliations.reconcile(new RecoveryFullReconciliationRequest(
                text(context, "recoveryRequestId"), text(context, "episodeId"),
                text(context, "taskId"), text(binding, "ruleVersionsDigest"),
                text(binding, "memberSetDigest"), text(binding, "watermarksDigest"),
                backfillSummaryDigest, text(binding, "traceId")));
    }

    private static RecoveryValidationExecution dependencyFailure(boolean retryable) {
        return RecoveryValidationExecution.failed(retryable
                ? RecoveryValidationErrorCode.DEPENDENCY_UNAVAILABLE
                : RecoveryValidationErrorCode.PROVIDER_NOT_INSTALLED);
    }

    private RecoveryValidationResult result(
            JsonNode context, JsonNode binding,
            RecoveryBackfillResult backfill,
            RecoveryFullReconciliationResult reconciliation,
            RecoverySampleRecomputeOutcome.BoundResult sample) {
        var summary = sample.summary();
        RecoveryValidationReadinessEvidence readiness = readiness(
                context.required("readinessEvidence"));
        if (!policy.policyVersion().equals(text(binding, "qualityRecoveryPolicyVersion"))
                || !policy.contractDigest().equals(
                    text(binding, "qualityRecoveryPolicyDigest"))) {
            throw invalid();
        }
        readiness.requireFrozenAuthority(policy, sourceClasses);
        long reconciliationMismatch = reconciliation.mismatchCount();
        boolean qualified = readiness.qualified() && reconciliationMismatch == 0
                && reconciliation.expectedCount() == reconciliation.actualCount()
                && summary.selectedCount() >= Math.min(summary.populationCount(), 100)
                && summary.mismatchCount() == 0;
        RecoveryValidationResult.ResultErrorCode error = qualified ? null
                : !readiness.qualified()
                    ? RecoveryValidationResult.ResultErrorCode.READINESS_NOT_MET
                : reconciliationMismatch != 0
                    ? RecoveryValidationResult.ResultErrorCode.RECONCILIATION_MISMATCH
                    : summary.mismatchCount() != 0
                        ? RecoveryValidationResult.ResultErrorCode.SAMPLE_MISMATCH
                        : RecoveryValidationResult.ResultErrorCode.SAMPLE_INSUFFICIENT;
        String sampleSummaryDigest = digest(String.join("\n",
                summary.providerVersion(), summary.selectionSeed(),
                Long.toString(summary.populationCount()),
                Integer.toString(summary.selectedCount()),
                summary.strataSummaryDigest(), Integer.toString(summary.mismatchCount())));
        String resultDigest = digest(String.join("\n", text(context, "inputDigest"),
                backfill.summaryDigest(), reconciliation.summaryDigest(), sampleSummaryDigest,
                readiness.evidenceDigest(),
                Boolean.toString(qualified)));
        return new RecoveryValidationResult(
                ids.get(), UUID.fromString(text(context, "jobId")),
                number(context, "jobVersion"),
                UUID.fromString(text(context, "recoveryRequestId")),
                UUID.fromString(text(context, "episodeId")),
                UUID.fromString(text(context, "taskId")),
                RecoveryValidationJobStatus.SUCCEEDED, text(context, "inputDigest"),
                "QRP-1.0.0", text(binding, "qualityRecoveryPolicyDigest"),
                backfill.startWatermarkDigest(), backfill.targetWatermarkDigest(),
                backfill.summaryDigest(), reconciliation.expectedCount(),
                reconciliation.actualCount(), reconciliationMismatch,
                reconciliation.summaryDigest(),
                "QUALITY-RECOVERY-SAMPLE-SUMMARY-1.0.0", sampleSummaryDigest,
                summary.populationCount(), summary.selectedCount(),
                summary.strata().stream().map(stratum ->
                        new RecoveryValidationResult.StratumSummary(
                                stratum.stratumCode(), stratum.populationCount(),
                                stratum.selectedCount(), stratum.mismatchCount(),
                                stratum.summaryDigest())).toList(),
                summary.strataSummaryDigest(), summary.expectedDigest(), summary.actualDigest(),
                summary.mismatchCount(), readiness, qualified, error,
                summary.completedAt(), resultDigest, text(binding, "traceId"));
    }

    private RecoveryValidationReadinessEvidence readiness(JsonNode value) {
        if (!value.isObject()) throw invalid();
        JsonNode quality = value.required("qualityEvidence");
        JsonNode batch = value.required("batchEvidence");
        JsonNode backfill = value.required("backfillEvidence");
        return new RecoveryValidationReadinessEvidence(
                number(value, "episodeGeneration"), number(value, "expectedEpisodeVersion"),
                number(value, "expectedTaskVersion"), text(value, "sourceId"),
                text(value, "dependencyId"), text(value, "ruleVersionsDigest"),
                text(value, "memberSetDigest"), text(value, "watermarksDigest"),
                text(value, "sourceClass"), text(value, "sourceSchemaVersion"),
                text(value, "sourceSchemaDigest"), text(value, "dependencyVersion"),
                text(value, "dependencyDigest"),
                java.util.stream.StreamSupport.stream(
                        value.required("eligibilityBindings").spliterator(), false)
                        .map(binding -> new RecoveryValidationReadinessEvidence.EligibilityBinding(
                                UUID.fromString(text(binding, "eligibilityId")),
                                text(binding, "ruleId"), text(binding, "ruleVersion"),
                                number(binding, "expectedAggregateVersion")))
                        .toList(),
                text(value, "currentBindingDigest"),
                new RecoveryValidationReadinessEvidence.QualityEvidence(
                        text(quality, "snapshotIdsDigest"),
                        text(quality, "snapshotHashesDigest"),
                        text(quality, "metricResultsDigest"), bool(quality, "allRequiredMetricsPassed"),
                        text(quality, "sourceWatermark"),
                        text(quality, "dependencyWatermark")),
                new RecoveryValidationReadinessEvidence.BatchEvidence(
                        text(batch, "sequenceEvidenceDigest"),
                        Math.toIntExact(number(batch, "requiredConsecutivePassedBatches")),
                        number(batch, "actualConsecutivePassedBatches")),
                new RecoveryValidationReadinessEvidence.BackfillEvidence(
                        text(backfill, "lastKnownGoodWatermark"),
                        instant(backfill, "trustedNow"),
                        Math.toIntExact(number(backfill, "lookbackDays")),
                        text(backfill, "backfillStartWatermark"),
                        text(backfill, "backfillCompletedWatermark"),
                        text(backfill, "evidenceDigest"), text(backfill, "status")),
                bool(value, "requiredMembersEligible"),
                java.util.stream.StreamSupport.stream(
                        value.required("missingEvidenceCodes").spliterator(), false)
                        .map(code -> RecoveryValidationReadinessEvidence.MissingEvidenceCode
                                .valueOf(code.asText()))
                        .toList(),
                number(value, "impactAlreadyExpiredCount"),
                number(value, "impactPotentiallyActionableCount"),
                number(value, "impactExpectedToExpireCount"),
                instant(value, "trustedStartedAt"), text(value, "evidenceDigest"));
    }

    private JsonNode context(UUID jobId) {
        String value = jdbc.queryForObject("""
                select ingestion_quality.iq_load_recovery_validation_execution_context(
                    ?,statement_timestamp())::text
                """, String.class, jobId);
        try {
            JsonNode root = json.readTree(Objects.requireNonNull(value));
            if (!root.isObject()) throw invalid();
            return root;
        } catch (JacksonException malformed) {
            throw new IllegalStateException(
                    "INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID", malformed);
        }
    }

    private List<RecoverySampleNormalizedInput.Stratum> strata(JsonNode node) {
        if (!node.isArray() || node.isEmpty() || node.size() > 128) throw invalid();
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .map(stratum -> new RecoverySampleNormalizedInput.Stratum(
                        text(stratum, "code"), number(stratum, "populationCount"),
                        java.util.stream.StreamSupport.stream(
                                stratum.required("selectedWindows").spliterator(), false)
                                .map(window -> new RecoverySampleNormalizedInput.Window(
                                        text(window, "selectionRankDigest"),
                                        text(window, "expectedDigest"),
                                        text(window, "normalizedInputDigest")))
                                .toList()))
                .toList();
    }

    private String sampleRequestDigest(JsonNode context, JsonNode binding, String selectionRef) {
        String canonical = String.join("\n", PROVIDER_VERSION,
                text(context, "recoveryRequestId"), text(context, "episodeId"),
                text(context, "taskId"), text(binding, "ruleVersionsDigest"),
                text(binding, "memberSetDigest"), text(binding, "watermarksDigest"),
                "QRP-1.0.0", text(binding, "qualityRecoveryPolicyDigest"), selectionRef,
                text(binding, "selectionSeed"), "100", text(binding, "traceId"));
        return digest(canonical);
    }

    private static String canonicalStrata(List<RecoverySampleNormalizedInput.Stratum> strata) {
        return strata.stream().map(stratum -> stratum.code() + "\n"
                + stratum.populationCount() + "\n"
                + stratum.windows().stream().map(window -> String.join("\n",
                        window.selectionRankDigest(), window.expectedDigest(),
                        window.normalizedInputDigest())).collect(
                        java.util.stream.Collectors.joining("\n--\n")))
                .collect(java.util.stream.Collectors.joining("\n====\n"));
    }

    private static String resume(JsonNode context, String phase) {
        return "resume:v1:" + digestHex(text(context, "inputDigest") + "\n" + phase);
    }

    private Instant trustedNow() {
        Instant value = Objects.requireNonNull(time.get());
        if (value.getNano() % 1_000 != 0) throw invalid();
        return value;
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

    private static Instant instant(JsonNode root, String name) {
        try {
            Instant value = Instant.parse(text(root, name));
            if (value.getNano() % 1_000 != 0) throw invalid();
            return value;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static String digest(String value) { return "sha256:" + digestHex(value); }

    private static String digestHex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID");
    }
}
