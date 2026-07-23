package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchQueryPort;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchResultSlice;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchRow;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchSnapshot;
import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchCriteria;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Parameterized PostgreSQL query adapter over the rebuildable projection only. */
public final class JdbcAuditSearchQueryRepository implements AuditSearchQueryPort {
    private static final String COLUMNS = """
            record_id, ledger_sequence, occurred_at, outcome, fact_schema_version,
            policy_version, retention_schedule_version, actor_search_token, object_type,
            object_search_token, action, trace_id, producer_module, event_type, reason_code,
            business_action_category, business_object_category, role_package_summary,
            projection_scope, source_network_recorded
            """;

    private final JdbcTemplate jdbc;

    public JdbcAuditSearchQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
    }

    @Override
    public AuditSearchSnapshot snapshot() {
        return jdbc.queryForObject("""
                select h.ledger_sequence source_head,
                       w.ledger_sequence projection_watermark,
                       w.projected_at data_cutoff_at
                from audit_operations.ao_audit_ledger_head h
                cross join audit_operations.ao_audit_search_projection_watermark w
                where h.singleton_id=1 and w.singleton_id=1
                """, (result, ignored) -> new AuditSearchSnapshot(
                    result.getLong("source_head"),
                    result.getLong("projection_watermark"),
                    result.getTimestamp("data_cutoff_at").toInstant()));
    }

    @Override
    public AuditSearchResultSlice search(
            AuditSearchCriteria criteria,
            List<String> actorTokens,
            List<String> objectTokens,
            long asOfSequence) {
        StringBuilder where = new StringBuilder(" where ledger_sequence<=?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(asOfSequence);
        in(where, parameters, "actor_search_token", actorTokens);
        if (criteria.objectType() != null) {
            where.append(" and object_type=?");
            parameters.add(criteria.objectType());
        }
        in(where, parameters, "object_search_token", objectTokens);
        equal(where, parameters, "action", criteria.action());
        if (criteria.occurredFrom() != null) {
            where.append(" and occurred_at>=?");
            parameters.add(Timestamp.from(criteria.occurredFrom()));
        }
        if (criteria.occurredTo() != null) {
            where.append(" and occurred_at<?");
            parameters.add(Timestamp.from(criteria.occurredTo()));
        }
        equal(where, parameters, "outcome", criteria.outcome());
        equal(where, parameters, "trace_id", criteria.traceId());

        Long total = jdbc.queryForObject(
                "select count(*) from audit_operations.ao_audit_search_projection" + where,
                Long.class,
                parameters.toArray());
        List<Object> pageParameters = new ArrayList<>(parameters);
        pageParameters.add(criteria.size());
        pageParameters.add(Math.multiplyExact(criteria.page(), criteria.size()));
        List<AuditSearchRow> rows = jdbc.query(
                "select " + COLUMNS + " from audit_operations.ao_audit_search_projection"
                        + where + " order by occurred_at desc, ledger_sequence desc limit ? offset ?",
                this::row,
                pageParameters.toArray());
        return new AuditSearchResultSlice(rows, total == null ? 0 : total);
    }

    private static void equal(
            StringBuilder where, List<Object> parameters, String column, String value) {
        if (value != null) {
            where.append(" and ").append(column).append("=?");
            parameters.add(value);
        }
    }

    private static void in(
            StringBuilder where, List<Object> parameters, String column, List<String> values) {
        if (!values.isEmpty()) {
            where.append(" and ").append(column).append(" in (")
                    .append("?,".repeat(values.size()), 0, values.size() * 2 - 1)
                    .append(')');
            parameters.addAll(values);
        }
    }

    private AuditSearchRow row(ResultSet result, int ignored) throws SQLException {
        return new AuditSearchRow(
                result.getObject("record_id", java.util.UUID.class),
                result.getLong("ledger_sequence"),
                result.getTimestamp("occurred_at").toInstant(),
                result.getString("outcome"),
                result.getString("fact_schema_version"),
                result.getString("policy_version"),
                result.getString("retention_schedule_version"),
                result.getString("actor_search_token"),
                result.getString("object_type"),
                result.getString("object_search_token"),
                result.getString("action"),
                result.getString("trace_id"),
                result.getString("producer_module"),
                result.getString("event_type"),
                result.getString("reason_code"),
                result.getString("business_action_category"),
                result.getString("business_object_category"),
                result.getString("role_package_summary"),
                result.getString("projection_scope"),
                result.getBoolean("source_network_recorded"));
    }
}
