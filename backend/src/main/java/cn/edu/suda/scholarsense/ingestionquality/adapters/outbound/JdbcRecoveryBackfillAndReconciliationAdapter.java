package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryBackfillPort;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryBackfillRequest;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryBackfillResult;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryFullReconciliationPort;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryFullReconciliationRequest;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryFullReconciliationResult;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Executes the owner-side historical scan and full reconciliation closed functions. */
public final class JdbcRecoveryBackfillAndReconciliationAdapter
        implements RecoveryBackfillPort, RecoveryFullReconciliationPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcRecoveryBackfillAndReconciliationAdapter(
            JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public RecoveryBackfillResult execute(RecoveryBackfillRequest request) {
        JsonNode value = call(
                "select ingestion_quality.iq_execute_recovery_backfill(?,?,?,?,?,?,?,?)::text",
                UUID.fromString(request.recoveryRequestId()),
                UUID.fromString(request.episodeId()),
                UUID.fromString(request.taskId()),
                request.ruleVersionsDigest(),
                request.memberSetDigest(),
                request.startWatermarkDigest(),
                request.targetWatermarkDigest(),
                request.traceId());
        return RecoveryBackfillResult.available(
                text(value, "startWatermarkDigest"),
                text(value, "targetWatermarkDigest"),
                number(value, "processedCount"),
                text(value, "summaryDigest"),
                instant(value, "completedAt"),
                request.traceId());
    }

    @Override
    public RecoveryFullReconciliationResult reconcile(
            RecoveryFullReconciliationRequest request) {
        JsonNode value = call(
                "select ingestion_quality.iq_execute_recovery_full_reconciliation(?,?,?,?,?,?,?,?)::text",
                UUID.fromString(request.recoveryRequestId()),
                UUID.fromString(request.episodeId()),
                UUID.fromString(request.taskId()),
                request.ruleVersionsDigest(),
                request.memberSetDigest(),
                request.watermarksDigest(),
                request.backfillSummaryDigest(),
                request.traceId());
        return RecoveryFullReconciliationResult.available(
                number(value, "expectedCount"),
                number(value, "actualCount"),
                number(value, "mismatchCount"),
                text(value, "summaryDigest"),
                instant(value, "completedAt"),
                request.traceId());
    }

    private JsonNode call(String sql, Object... parameters) {
        try {
            JsonNode value = json.readTree(Objects.requireNonNull(
                    jdbc.queryForObject(sql, String.class, parameters)));
            if (!value.isObject()) {
                throw invalid();
            }
            return value;
        } catch (JacksonException malformed) {
            throw invalid();
        }
    }

    private static String text(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw invalid();
        }
        return node.asText();
    }

    private static long number(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isIntegralNumber() || node.asLong() < 0) {
            throw invalid();
        }
        return node.asLong();
    }

    private static Instant instant(JsonNode value, String field) {
        try {
            Instant result = Instant.parse(text(value, field));
            if (result.getNano() % 1_000 != 0) {
                throw invalid();
            }
            return result;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException(
                "INGESTION_QUALITY_RECOVERY_DEPENDENCY_RESULT_INVALID");
    }
}
