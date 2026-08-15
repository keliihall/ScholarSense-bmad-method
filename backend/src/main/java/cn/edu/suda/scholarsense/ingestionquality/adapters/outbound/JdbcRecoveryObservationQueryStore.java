package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryObservationQueryPort;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryObservationView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Closed-function adapter for the privacy-minimized observation projection. */
public final class JdbcRecoveryObservationQueryStore implements RecoveryObservationQueryPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcRecoveryObservationQueryStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.json = java.util.Objects.requireNonNull(json);
    }

    @Override
    public Optional<RecoveryObservationView> findByTaskId(UUID taskId) {
        String value = jdbc.queryForObject("""
                select ingestion_quality.iq_load_recovery_observation_view(?)::text
                """, String.class, taskId);
        if (value == null) return Optional.empty();
        try {
            JsonNode root = json.readTree(value);
            return Optional.of(new RecoveryObservationView(
                    uuid(root, "recoveryId"), number(root, "recoveryVersion"),
                    uuid(root, "taskId"),
                    number(root, "taskVersion"), number(root, "generation"),
                    text(root, "sourceClass"), text(root, "policyVersion"),
                    text(root, "policyDigest"), text(root, "status"),
                    text(root, "finalizationState"),
                    optionalUuid(root, "approvalId"),
                    optionalNumber(root, "approvalVersion"),
                    Math.toIntExact(number(root, "consecutivePassedBatches")),
                    Math.toIntExact(number(root, "requiredPassedBatches")),
                    number(root, "observedDurationMicros"),
                    number(root, "requiredDurationMicros"),
                    text(root, "observationDuration"), nullableText(root, "watermark"),
                    instant(root, "recoveringStartedAt"), instant(root, "lastObservedAt"),
                    nullableInstant(root, "latestActionableAt"),
                    strings(root, "failedMembers"),
                    nullableText(root, "failureReasonCode"),
                    text(root, "eligibilityStatus"), text(root, "taskStatus"),
                    nullableInstant(root, "taskClosedAt"),
                    nullableText(root, "ownerResultDigest"), text(root, "deliveryStatus"),
                    number(root, "deliveryAttempt"),
                    nullableInstant(root, "deliveryNextAttemptAt"),
                    number(root, "eligibleForHandoffWindowCount"),
                    number(root, "historyOnlyWindowCount"), text(root, "traceId")));
        } catch (RuntimeException malformed) {
            throw new IllegalStateException("INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID",
                    malformed);
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid();
        return value.asText();
    }

    private static String nullableText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : text(root, field);
    }

    private static long number(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber()) throw invalid();
        return value.asLong();
    }

    private static Long optionalNumber(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : number(root, field);
    }

    private static List<String> strings(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray() || value.size() > 128) throw invalid();
        ArrayList<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (!item.isTextual()) throw invalid();
            result.add(item.asText());
        });
        return List.copyOf(result);
    }

    private static UUID uuid(JsonNode root, String field) {
        UUID value = UUID.fromString(text(root, field));
        if (value.version() != 7 || value.variant() != 2) throw invalid();
        return value;
    }

    private static UUID optionalUuid(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : uuid(root, field);
    }

    private static Instant instant(JsonNode root, String field) {
        Instant value = Instant.parse(text(root, field));
        if (value.getNano() % 1_000 != 0) throw invalid();
        return value;
    }

    private static Instant nullableInstant(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : instant(root, field);
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID");
    }
}
