package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.api.AuditProducerBacklogPort;
import cn.edu.suda.scholarsense.auditoperations.api.AuditProducerBacklogSnapshot;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchRelayClock;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;

/** Audit-operations producer backlog; unavailable measurements fail closed. */
public final class JdbcAuditOperationsBacklogSource implements AuditProducerBacklogPort {
    private final JdbcTemplate jdbc;
    private final AuditSearchRelayClock clock;

    public JdbcAuditOperationsBacklogSource(JdbcTemplate jdbc, AuditSearchRelayClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public AuditProducerBacklogSnapshot current() {
        var now = clock.now();
        try {
            return jdbc.queryForObject("""
                    select count(*) filter (where delivery_status in ('pending','retrying','failed')) unconfirmed,
                      coalesce(greatest(0, floor(extract(epoch from (? - min(created_at) filter
                        (where delivery_status in ('pending','retrying','failed')))))),0)::bigint oldest,
                      count(*) filter (where delivery_status in ('pending','retrying')) retryable,
                      coalesce(greatest(0, floor(extract(epoch from (? - min(created_at) filter
                        (where delivery_status in ('pending','retrying')))))),0)::bigint retryable_oldest,
                      coalesce(bool_or(delivery_status in ('failed','quarantine')),false) permanent
                    from audit_operations.ao_local_audit_outbox
                    """, (result, ignored) -> new AuditProducerBacklogSnapshot(
                            result.getLong("unconfirmed"), result.getLong("oldest"),
                            result.getLong("retryable"), result.getLong("retryable_oldest"),
                            result.getBoolean("permanent"), now, true),
                    Timestamp.from(now), Timestamp.from(now));
        } catch (RuntimeException unavailable) {
            return new AuditProducerBacklogSnapshot(0, 0, 0, 0, true, now, false);
        }
    }
}
