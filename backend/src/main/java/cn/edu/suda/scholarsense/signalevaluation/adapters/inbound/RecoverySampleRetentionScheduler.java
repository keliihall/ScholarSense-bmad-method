package cn.edu.suda.scholarsense.signalevaluation.adapters.inbound;

import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** Signal-evaluation-owned, legal-hold-aware recovery sample retention workload. */
public final class RecoverySampleRetentionScheduler {
    private final JdbcTemplate jdbc;
    private final TrustedTimeSource time;

    public RecoverySampleRetentionScheduler(JdbcTemplate jdbc, TrustedTimeSource time) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.time = java.util.Objects.requireNonNull(time);
    }

    @Scheduled(fixedDelayString =
            "${scholarsense.signal-evaluation.recovery-retention-delay-ms:3600000}")
    public long cleanup() {
        Long deleted = jdbc.queryForObject(
                "select signal_evaluation.se_cleanup_recovery_sample_expired(?)",
                Long.class, Timestamp.from(time.now().instant()));
        if (deleted == null || deleted < 0) {
            throw new IllegalStateException("SIGNAL_EVALUATION_RECOVERY_RETENTION_UNAVAILABLE");
        }
        return deleted;
    }
}
