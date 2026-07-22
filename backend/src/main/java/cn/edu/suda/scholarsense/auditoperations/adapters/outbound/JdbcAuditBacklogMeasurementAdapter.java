package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.api.AuditProducerBacklogPort;
import cn.edu.suda.scholarsense.auditoperations.api.AuditProducerBacklogSnapshot;
import cn.edu.suda.scholarsense.auditoperations.application.AuditBacklogMeasurement;
import cn.edu.suda.scholarsense.auditoperations.application.AuditBacklogMeasurementPort;
import cn.edu.suda.scholarsense.auditoperations.application.FindingRepository;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composes producer-owned backlog with audit-owned integrity state without reading producer tables. */
public final class JdbcAuditBacklogMeasurementAdapter implements AuditBacklogMeasurementPort {
    private final AuditProducerBacklogPort producer;
    private final FindingRepository findings;
    private final JdbcTemplate jdbc;

    public JdbcAuditBacklogMeasurementAdapter(
            AuditProducerBacklogPort producer, FindingRepository findings, JdbcTemplate jdbc) {
        this.producer = Objects.requireNonNull(producer);
        this.findings = Objects.requireNonNull(findings);
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public AuditBacklogMeasurement current() {
        AuditProducerBacklogSnapshot source;
        try {
            source = producer.current();
            Boolean chainHealthy = jdbc.query("""
                    select healthy from audit_operations.ao_verification_run
                    order by completed_at desc, run_id desc limit 1
                    """, (result, ignored) -> result.getBoolean("healthy")).stream().findFirst().orElse(false);
            boolean permanentFindingActive = findings.hasActivePermanentFinding();
            boolean includeFailed = source.permanentFailureActive() && permanentFindingActive;
            return new AuditBacklogMeasurement(
                    includeFailed ? source.unconfirmedCount() : source.retryableUnconfirmedCount(),
                    includeFailed
                            ? source.oldestUnconfirmedAgeSeconds()
                            : source.oldestRetryableUnconfirmedAgeSeconds(),
                    permanentFindingActive,
                    Boolean.TRUE.equals(chainHealthy), source.measuredAt(), source.available());
        } catch (RuntimeException unavailable) {
            java.time.Instant now = java.time.Instant.EPOCH;
            try {
                AuditProducerBacklogSnapshot fallback = producer.current();
                now = fallback.measuredAt();
            } catch (RuntimeException ignored) {
                // A deterministic stale observation keeps this adapter fail closed without logging data.
            }
            return new AuditBacklogMeasurement(0, 0, true, false, now, false);
        }
    }
}
