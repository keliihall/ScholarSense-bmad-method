package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.shared.outbox.AuditProducerBacklogPort;
import cn.edu.suda.scholarsense.shared.outbox.AuditProducerBacklogSnapshot;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcCatalogAuditBacklog implements AuditProducerBacklogPort {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcCatalogAuditBacklog(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    @Override
    public AuditProducerBacklogSnapshot current() {
        Instant now = clock.instant();
        try {
            return jdbc.queryForObject("""
                    select count(*) filter (where status in ('pending','retrying','failed')) unconfirmed,
                      coalesce(greatest(0,floor(extract(epoch from (? - min(created_at)
                        filter (where status in ('pending','retrying','failed')))))),0)::bigint oldest,
                      count(*) filter (where status in ('pending','retrying')) retryable,
                      coalesce(greatest(0,floor(extract(epoch from (? - min(created_at)
                        filter (where status in ('pending','retrying')))))),0)::bigint retryable_oldest,
                      coalesce(bool_or(status='failed'),false) permanent
                    from ingestion_quality.iq_local_audit_outbox
                    """, (row, ignored) -> new AuditProducerBacklogSnapshot(
                            row.getLong("unconfirmed"), row.getLong("oldest"),
                            row.getLong("retryable"), row.getLong("retryable_oldest"),
                            row.getBoolean("permanent"), now, true), Timestamp.from(now), Timestamp.from(now));
        } catch (RuntimeException unavailable) {
            return new AuditProducerBacklogSnapshot(0, 0, 0, 0, true, now, false);
        }
    }
}
