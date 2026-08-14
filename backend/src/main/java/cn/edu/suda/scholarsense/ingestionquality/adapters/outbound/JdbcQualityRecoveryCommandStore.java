package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryCommandContext;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryCommandStorePort;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryExecutionCommit;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryRequestState;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryConfirmationClaim;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryConfirmationRelayPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exact closed-function adapter for recovery commands and safe internal projections. */
public final class JdbcQualityRecoveryCommandStore
        implements QualityRecoveryCommandStorePort, QualityRecoveryConfirmationRelayPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcQualityRecoveryCommandStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public Optional<QualityRecoveryCommandContext> loadContext(UUID taskId) {
        String value = jdbc.queryForObject("""
                select ingestion_quality.iq_load_quality_recovery_command_context(?)::text
                """, String.class, taskId);
        if (value == null) return Optional.empty();
        JsonNode root = parse(value);
        ArrayList<QualityRecoveryCommandContext.AffectedRule> rules = new ArrayList<>();
        JsonNode affected = required(root, "affectedRules");
        if (!affected.isArray() || affected.isEmpty() || affected.size() > 128) throw invalid();
        for (JsonNode rule : affected) {
            rules.add(new QualityRecoveryCommandContext.AffectedRule(
                    text(rule, "ruleId"), text(rule, "ruleVersion"),
                    text(rule, "ruleVersionDigest")));
        }
        return Optional.of(new QualityRecoveryCommandContext(
                uuid(root, "taskId"), number(root, "taskVersion"),
                uuid(root, "episodeId"), number(root, "episodeVersion"),
                number(root, "episodeGeneration"), text(root, "sourceId"),
                text(root, "dependencyId"), rules,
                text(root, "affectedRuleVersionsDigest"),
                text(root, "memberSetDigest"), text(root, "watermarksDigest"),
                text(root, "currentState"), text(root, "targetState")));
    }

    @Override
    public Optional<QualityRecoveryRequestState> findRequest(UUID recoveryRequestId) {
        String value = jdbc.queryForObject("""
                select ingestion_quality.iq_find_quality_recovery_request(?)::text
                """, String.class, recoveryRequestId);
        if (value == null) return Optional.empty();
        JsonNode root = parse(value);
        return Optional.of(requestState(root));
    }

    @Override
    public Optional<QualityRecoveryRequestState> findRequestReplay(
            String idempotencyKeyDigest,
            String inputDigest,
            UUID expectedRecoveryRequestId) {
        String value = jdbc.queryForObject("""
                select ingestion_quality.iq_find_quality_recovery_idempotency(
                    ?,?,?)::text
                """, String.class, idempotencyKeyDigest, inputDigest,
                expectedRecoveryRequestId);
        if (value == null) return Optional.empty();
        JsonNode response = parse(value);
        UUID requestId = uuid(response, "recoveryRequestId");
        if (!requestId.equals(expectedRecoveryRequestId)) throw invalid();
        return findRequest(requestId);
    }

    @Override
    public Optional<QualityRecoveryExecutionCommit> findExecutionReplay(
            String idempotencyKeyDigest,
            String inputDigest,
            UUID expectedRecoveryRequestId) {
        String value = jdbc.queryForObject("""
                select ingestion_quality.iq_find_quality_recovery_idempotency(
                    ?,?,?)::text
                """, String.class, idempotencyKeyDigest, inputDigest,
                expectedRecoveryRequestId);
        if (value == null) return Optional.empty();
        return Optional.of(executionCommit(parse(value)));
    }

    @Override
    public QualityRecoveryRequestState submitRequest(
            String idempotencyKeyDigest, Map<String, Object> value) {
        JsonNode response = callJson("""
                select ingestion_quality.iq_submit_quality_recovery_request(?,?::jsonb)::text
                """, idempotencyKeyDigest, encode(value));
        return findRequest(uuid(response, "recoveryRequestId")).orElseThrow(
                JdbcQualityRecoveryCommandStore::invalid);
    }

    @Override
    public QualityRecoveryRequestState submitRequestWithValidationJob(
            String requestIdempotencyKeyDigest,
            Map<String, Object> request,
            String jobIdempotencyKeyDigest,
            UUID jobId) {
        JsonNode response = callJson("""
                select ingestion_quality.iq_submit_quality_recovery_with_validation(
                    ?,?::jsonb,?,?)::text
                """, requestIdempotencyKeyDigest, encode(request),
                jobIdempotencyKeyDigest, jobId);
        return findRequest(uuid(response, "recoveryRequestId")).orElseThrow(
                JdbcQualityRecoveryCommandStore::invalid);
    }

    @Override
    public QualityRecoveryRequestState submitValidationJob(
            String idempotencyKeyDigest, Map<String, Object> value) {
        JsonNode response = callJson("""
                select ingestion_quality.iq_submit_recovery_validation_job(?,?::jsonb)::text
                """, idempotencyKeyDigest, encode(value));
        return findRequest(uuid(response, "recoveryRequestId")).orElseThrow(
                JdbcQualityRecoveryCommandStore::invalid);
    }

    @Override
    public QualityRecoveryRequestState bindApproval(
            String idempotencyKeyDigest,
            String inputDigest,
            Map<String, Object> value,
            Instant trustedNow) {
        JsonNode response = callJson("""
                select ingestion_quality.iq_bind_quality_recovery_approval(
                    ?,?,?::jsonb,?)::text
                """, idempotencyKeyDigest, inputDigest,
                encode(value), Timestamp.from(trustedNow));
        return findRequest(uuid(response, "recoveryRequestId")).orElseThrow(
                JdbcQualityRecoveryCommandStore::invalid);
    }

    @Override
    public QualityRecoveryExecutionCommit execute(
            String idempotencyKeyDigest, Map<String, Object> value) {
        JsonNode root = callJson("""
                select ingestion_quality.iq_execute_quality_recovery(
                    ?,?::jsonb)::text
                """, idempotencyKeyDigest, encode(value));
        return executionCommit(root);
    }

    @Override
    public java.util.List<QualityRecoveryConfirmationClaim> claim(
            int limit, Instant trustedNow) {
        return jdbc.query("""
                select value::text
                  from ingestion_quality.iq_claim_quality_recovery_confirmations(?,?) value
                """, (row, ignored) -> confirmationClaim(parse(row.getString(1))),
                limit, Timestamp.from(trustedNow));
    }

    @Override
    public boolean markDelivered(UUID eventId, String payloadDigest, Instant trustedNow) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select ingestion_quality.iq_mark_quality_recovery_confirmation_delivered(?,?,?)
                """, Boolean.class, eventId, payloadDigest, Timestamp.from(trustedNow)));
    }

    @Override
    public boolean release(
            UUID eventId, String payloadDigest, String errorCode, Instant trustedNow) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select ingestion_quality.iq_release_quality_recovery_confirmation(?,?,?,?)
                """, Boolean.class, eventId, payloadDigest, errorCode,
                Timestamp.from(trustedNow)));
    }

    private static QualityRecoveryConfirmationClaim confirmationClaim(JsonNode root) {
        return new QualityRecoveryConfirmationClaim(
                uuid(root, "outboxEventId"), uuid(root, "leaseId"),
                number(root, "leaseVersion"), text(root, "leaseDigest"),
                uuid(root, "executionJti"), text(root, "requestDigest"),
                text(root, "ownerCommitId"), instant(root, "ownerCommittedAt"),
                text(root, "ownerResultDigest"), text(root, "traceId"),
                text(root, "payloadDigest"));
    }

    private static QualityRecoveryExecutionCommit executionCommit(JsonNode root) {
        return new QualityRecoveryExecutionCommit(
                uuid(root, "recoveryRequestId"), uuid(root, "taskId"),
                uuid(root, "episodeId"), text(root, "state"),
                bool(root, "transitionApplied"), uuid(root, "executionJti"),
                text(root, "ownerCommitId"), text(root, "ownerResultDigest"),
                uuid(root, "confirmationOutboxEventId"),
                instant(root, "ownerCommittedAt"), text(root, "traceId"));
    }

    private JsonNode callJson(String sql, Object... parameters) {
        return parse(Objects.requireNonNull(
                jdbc.queryForObject(sql, String.class, parameters)));
    }

    private QualityRecoveryRequestState requestState(JsonNode root) {
        JsonNode bindingNode = required(root, "binding");
        if (!bindingNode.isObject()) throw invalid();
        return new QualityRecoveryRequestState(
                uuid(root, "recoveryRequestId"), number(root, "requestVersion"),
                uuid(root, "taskId"), uuid(root, "episodeId"), text(root, "status"),
                optionalUuid(root, "validationJobId"), optionalText(root, "validationStatus"),
                optionalText(root, "validationResultDigest"), optionalUuid(root, "previewId"),
                optionalNumber(root, "previewVersion"), optionalText(root, "previewDigest"),
                optionalInstant(root, "previewExpiresAt"), previewSummary(root.get("previewSummary")),
                optionalUuid(root, "approvalId"),
                optionalNumber(root, "approvalVersion"), optionalText(root, "approvalStatus"),
                text(root, "traceId"), objectMap(bindingNode));
    }

    private Map<String, Object> objectMap(JsonNode node) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            Object value = scalar(entry.getValue());
            if (value != null) values.put(entry.getKey(), value);
        });
        return Map.copyOf(values);
    }

    private static QualityRecoveryRequestState.PreviewSummary previewSummary(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isObject()) throw invalid();
        return new QualityRecoveryRequestState.PreviewSummary(
                text(value, "qualityRecoveryPolicyVersion"),
                number(value, "requiredConsecutivePassedBatches"),
                number(value, "actualConsecutivePassedBatches"),
                text(value, "observationDuration"), text(value, "backfillStatus"),
                number(value, "backfillLookbackDays"),
                number(value, "reconciliationExpectedCount"),
                number(value, "reconciliationActualCount"),
                number(value, "reconciliationMismatchCount"),
                number(value, "samplePopulationCount"),
                number(value, "sampleSelectedCount"),
                number(value, "sampleMismatchCount"), number(value, "sampleStrataCount"),
                number(value, "impactAlreadyExpiredCount"),
                number(value, "impactPotentiallyActionableCount"),
                number(value, "impactExpectedToExpireCount"),
                text(value, "finalActionabilityOwnerStory"));
    }

    private static Object scalar(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isTextual()) return value.asText();
        if (value.isIntegralNumber()) return value.asLong();
        if (value.isBoolean()) return value.asBoolean();
        throw invalid();
    }

    private JsonNode parse(String value) {
        try {
            JsonNode root = json.readTree(value);
            if (root == null || !root.isObject()) throw invalid();
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

    private static JsonNode required(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) throw invalid();
        return value;
    }

    private static String text(JsonNode root, String name) {
        JsonNode value = required(root, name);
        if (!value.isTextual() || value.asText().isBlank()) throw invalid();
        return value.asText();
    }

    private static String optionalText(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.asText().isBlank()) throw invalid();
        return value.asText();
    }

    private static long number(JsonNode root, String name) {
        JsonNode value = required(root, name);
        if (!value.isIntegralNumber()) throw invalid();
        return value.asLong();
    }

    private static Long optionalNumber(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber()) throw invalid();
        return value.asLong();
    }

    private static boolean bool(JsonNode root, String name) {
        JsonNode value = required(root, name);
        if (!value.isBoolean()) throw invalid();
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

    private static UUID optionalUuid(JsonNode root, String name) {
        String value = optionalText(root, name);
        if (value == null) return null;
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.version() != 7 || parsed.variant() != 2) throw invalid();
            return parsed;
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

    private static Instant optionalInstant(JsonNode root, String name) {
        String value = optionalText(root, name);
        return value == null ? null : instantText(value);
    }

    private static Instant instantText(String value) {
        try {
            Instant parsed = Instant.parse(value);
            if (parsed.getNano() % 1_000 != 0) throw invalid();
            return parsed;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID");
    }
}
