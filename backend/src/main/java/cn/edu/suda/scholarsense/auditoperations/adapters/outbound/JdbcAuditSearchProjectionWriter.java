package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchProjectionWriter;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerEntry;
import java.sql.Timestamp;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Typed projection writer; raw ledger payload is never copied into the searchable table. */
public final class JdbcAuditSearchProjectionWriter implements AuditSearchProjectionWriter {
    private final JdbcTemplate jdbc;

    public JdbcAuditSearchProjectionWriter(JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
    }

    @Override
    public void project(LedgerEntry entry) {
        var fact = entry.payload();
        Object policy = fact.authorizationContext().get("policyVersion");
        String policyVersion = policy instanceof String value ? value : "RFP-1.0.0";
        String actionCategory = fact.action().substring(0, fact.action().indexOf('.'));
        // V1 exposes only a bounded role-package hint. The full, unbounded role set remains in the
        // immutable fact and must never make a valid ledger append fail on the varchar(128) projection.
        String roleSummary = roleSummary(fact.roleIds());
        jdbc.update("""
                insert into audit_operations.ao_audit_search_projection (
                  ledger_sequence, record_id, occurred_at, outcome, fact_schema_version,
                  policy_version, retention_schedule_version, actor_search_token, object_type,
                  object_search_token, action, trace_id, producer_module, event_type, reason_code,
                  business_action_category, business_object_category, role_package_summary,
                  projection_scope, source_network_recorded)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entry.ledgerSequence(), entry.auditId(), Timestamp.from(fact.occurredAt()),
                fact.outcome(), fact.schemaVersion(), policyVersion, fact.retentionScheduleVersion(),
                fact.actorSearchToken(), fact.objectType(), fact.objectSearchToken(), fact.action(),
                fact.traceId(), fact.producerModule(), entry.eventType(), fact.reasonCode(),
                actionCategory, fact.objectType(), roleSummary, fact.projectionScope(),
                fact.sourceIpSearchToken() != null);
        int updated = jdbc.update("""
                update audit_operations.ao_audit_search_projection_watermark
                set ledger_sequence=?, projected_at=clock_timestamp()
                where singleton_id=1 and ledger_sequence=?
                """, entry.ledgerSequence(), entry.ledgerSequence() - 1);
        if (updated != 1) {
            throw new org.springframework.dao.ConcurrencyFailureException(
                    "AUDIT_SEARCH_PROJECTION_WATERMARK_CHANGED");
        }
    }

    static String roleSummary(java.util.List<String> roleIds) {
        return roleIds.isEmpty() ? null : roleIds.getFirst();
    }
}
