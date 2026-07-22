package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.api.AuditProducerBacklogPort;
import cn.edu.suda.scholarsense.auditoperations.api.AuditProducerBacklogSnapshot;
import cn.edu.suda.scholarsense.identityaccess.application.AuditRelayClock;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/** Identity-owned projection; no audit-operations component reads identity tables directly. */
public final class JdbcIdentityAuditBacklogSource implements AuditProducerBacklogPort {
    private final JdbcTemplate jdbc;
    private final AuditRelayClock clock;

    public JdbcIdentityAuditBacklogSource(JdbcTemplate jdbc, AuditRelayClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public AuditProducerBacklogSnapshot current() {
        Instant now = clock.now();
        try {
            return jdbc.queryForObject("""
                    select count(*) filter (
                             where delivery_status in ('pending', 'retrying', 'failed'))
                               as unconfirmed_count,
                           coalesce(greatest(0, floor(extract(epoch from (
                             ? - min(created_at) filter (
                               where delivery_status in ('pending', 'retrying', 'failed')))))), 0)::bigint
                               as oldest_age_seconds,
                           count(*) filter (where delivery_status in ('pending', 'retrying'))
                               as retryable_unconfirmed_count,
                           coalesce(greatest(0, floor(extract(epoch from (
                             ? - min(created_at) filter (
                               where delivery_status in ('pending', 'retrying')))))), 0)::bigint
                               as oldest_retryable_age_seconds,
                           coalesce(bool_or(delivery_status='failed'), false) as permanent_failure
                    from identity_access.ia_local_audit_outbox
                    """,
                    (result, ignored) -> new AuditProducerBacklogSnapshot(
                            result.getLong("unconfirmed_count"),
                            result.getLong("oldest_age_seconds"),
                            result.getLong("retryable_unconfirmed_count"),
                            result.getLong("oldest_retryable_age_seconds"),
                            result.getBoolean("permanent_failure"), now, true),
                    Timestamp.from(now),
                    Timestamp.from(now));
        } catch (RuntimeException unavailable) {
            return new AuditProducerBacklogSnapshot(0, 0, 0, 0, true, now, false);
        }
    }
}
