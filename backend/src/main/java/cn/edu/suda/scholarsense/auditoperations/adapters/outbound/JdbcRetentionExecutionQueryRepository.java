package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.application.RetentionExecution;
import cn.edu.suda.scholarsense.auditoperations.application.RetentionExecutionQueryPort;
import cn.edu.suda.scholarsense.auditoperations.application.RetentionExecutionState;
import cn.edu.suda.scholarsense.auditoperations.application.RetentionExecutionStep;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Read-only evidence adapter; it exposes no archive object locator. */
public final class JdbcRetentionExecutionQueryRepository implements RetentionExecutionQueryPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcRetentionExecutionQueryRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<RetentionExecution> find(UUID executionId) {
        Optional<RetentionExecution> execution = jdbc.query("""
                select execution_id, schedule_version, scope_type, fixture_id, scope_hash,
                       as_of_sequence, request_digest, state, attempt_no, fencing_token,
                       aggregate_version, source_ledger_head, projection_watermark,
                       archive_digest, non_production_evidence, unmet_guards::text,
                       trace_id, trusted_at
                from audit_operations.ao_retention_execution
                where execution_id = ?
                """, (result, ignored) -> new RetentionExecution(
                        "AUDIT-RETENTION-EXECUTION-1.0.0",
                        result.getObject("execution_id", UUID.class),
                        result.getString("schedule_version"), result.getString("scope_type"),
                        result.getString("fixture_id"), result.getString("scope_hash"),
                        result.getLong("as_of_sequence"), result.getString("request_digest"),
                        RetentionExecutionState.valueOf(result.getString("state").toUpperCase(Locale.ROOT)),
                        result.getLong("attempt_no"), result.getLong("fencing_token"),
                        result.getLong("aggregate_version"), "destroy-fixture",
                        result.getLong("source_ledger_head"), result.getLong("projection_watermark"),
                        result.getString("archive_digest"), "audit-retention-fixture-executor",
                        result.getTimestamp("trusted_at").toInstant(), result.getString("trace_id"),
                        result.getBoolean("non_production_evidence"),
                        strings(result.getString("unmet_guards")), List.of()), executionId)
                .stream().findFirst();
        if (execution.isEmpty()) return Optional.empty();
        List<RetentionExecutionStep> steps = jdbc.query("""
                select step_name, status, error_code
                from audit_operations.ao_retention_execution_step
                where execution_id = ?
                order by step_no
                """, (result, ignored) -> new RetentionExecutionStep(
                        result.getString("step_name"), result.getString("status"),
                        result.getString("error_code")), executionId);
        return Optional.of(execution.orElseThrow().withSteps(steps));
    }

    @SuppressWarnings("unchecked")
    private List<String> strings(String value) {
        try {
            return ((List<Object>) json.readValue(value, List.class)).stream()
                    .map(String::valueOf).toList();
        } catch (Exception corrupt) {
            throw new IllegalStateException("AUDIT_RETENTION_EVIDENCE_INVALID", corrupt);
        }
    }
}
