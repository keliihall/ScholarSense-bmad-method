package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryTask;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryTaskQueryCriteria;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryTaskQueryPort;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityTaskDeliveryProjection;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleVersionIdentity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Online-role adapter; one ID query plus two bounded bulk hydrations per page. */
public final class JdbcQualityRecoveryTaskQueryStore implements QualityRecoveryTaskQueryPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcQualityRecoveryTaskQueryStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public List<QualityRecoveryTask> findCurrent(QualityRecoveryTaskQueryCriteria criteria) {
        List<UUID> ids = jdbc.query("""
                select task_id
                  from ingestion_quality.iq_find_quality_recovery_task_ids(?,?,?,?,?)
                """, (row, ignored) -> row.getObject(1, UUID.class),
                criteria.sourceId(), criteria.status(), timestamp(criteria.afterOccurredAt()),
                criteria.afterTaskId(), criteria.limit());
        return hydrate(ids);
    }

    @Override
    public Optional<QualityRecoveryTask> findCurrentById(UUID taskId) {
        Objects.requireNonNull(taskId);
        List<QualityRecoveryTask> values = hydrate(List.of(taskId));
        if (values.size() > 1) throw invalid();
        return values.stream().findFirst();
    }

    private List<QualityRecoveryTask> hydrate(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        UUID[] page = ids.toArray(UUID[]::new);
        List<TaskRow> rows = jdbc.query("""
                select *
                  from ingestion_quality.iq_find_quality_recovery_task_page(?::uuid[])
                """, this::taskRow, (Object) page);
        if (rows.size() != ids.size()
                || !rows.stream().map(TaskRow::taskId).toList().equals(ids)) {
            if (ids.size() == 1 && rows.isEmpty()) return List.of();
            throw invalid();
        }
        Map<UUID, List<RuleVersionIdentity>> rules = new LinkedHashMap<>();
        ids.forEach(id -> rules.put(id, new ArrayList<>()));
        jdbc.query("""
                select *
                  from ingestion_quality.iq_find_quality_recovery_task_page_rules(?::uuid[])
                """, (row, ignored) -> {
                    UUID taskId = row.getObject("task_id", UUID.class);
                    List<RuleVersionIdentity> target = rules.get(taskId);
                    if (target == null) throw invalid();
                    target.add(new RuleVersionIdentity(
                            row.getString("rule_id"), row.getString("rule_version")));
                    return null;
                }, (Object) page);
        return rows.stream().map(row -> row.toDomain(rules.get(row.taskId()))).toList();
    }

    private TaskRow taskRow(ResultSet row, int ignored) throws SQLException {
        Timestamp next = row.getTimestamp("next_attempt_at");
        return new TaskRow(
                row.getObject("task_id", UUID.class),
                row.getObject("episode_id", UUID.class),
                row.getLong("episode_generation"), row.getString("source_id"),
                row.getString("dependency_id"), row.getString("owner_ref"),
                row.getString("priority"), instant(row, "due_at"), row.getString("status"),
                DataBatchPersistenceCodec.decodeUtf8(row.getBytes("watermark_utf8")),
                stringMap(row.getString("trigger")),
                stringMap(row.getString("current_evidence")),
                row.getLong("aggregate_version"), instant(row, "occurred_at"),
                new QualityTaskDeliveryProjection(
                        row.getString("delivery_target"), row.getString("delivery_status"),
                        row.getLong("delivery_attempt"), next == null ? null : next.toInstant(),
                        row.getLong("route_sequence")),
                trim(row.getString("trace_id")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> stringMap(String value) {
        try {
            Map<?, ?> raw = json.readValue(value, Map.class);
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            raw.forEach((key, item) -> {
                if (!(key instanceof String textKey) || !(item instanceof String textValue)) {
                    throw invalid();
                }
                result.put(textKey, textValue);
            });
            return Map.copyOf(result);
        } catch (JacksonException malformed) {
            throw new IllegalStateException(
                    "INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID", malformed);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        if (value == null) throw invalid();
        return value.toInstant();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID");
    }

    private record TaskRow(
            UUID taskId,
            UUID episodeId,
            long episodeGeneration,
            String sourceId,
            String dependencyId,
            String ownerRef,
            String priority,
            Instant dueAt,
            String status,
            String watermark,
            Map<String, String> trigger,
            Map<String, String> currentEvidence,
            long aggregateVersion,
            Instant occurredAt,
            QualityTaskDeliveryProjection delivery,
            String traceId) {
        QualityRecoveryTask toDomain(List<RuleVersionIdentity> affectedRules) {
            if (affectedRules == null || affectedRules.isEmpty()) throw invalid();
            return new QualityRecoveryTask(
                    taskId, episodeId, episodeGeneration, sourceId, dependencyId,
                    affectedRules, ownerRef, priority, dueAt, status, watermark, trigger,
                    currentEvidence, aggregateVersion, occurredAt, delivery, traceId);
        }
    }
}
