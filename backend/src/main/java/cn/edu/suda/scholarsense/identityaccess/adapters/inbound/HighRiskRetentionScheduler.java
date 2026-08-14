package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.HighRiskTrustedTimePort;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** Identity-access-owned legal-hold-aware retention; IQ never deletes these facts. */
public final class HighRiskRetentionScheduler {
    private final JdbcTemplate jdbc;
    private final HighRiskTrustedTimePort time;

    public HighRiskRetentionScheduler(JdbcTemplate jdbc, HighRiskTrustedTimePort time) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.time = java.util.Objects.requireNonNull(time);
    }

    @Scheduled(fixedDelayString = "${scholarsense.identity.high-risk-retention-delay-ms:3600000}")
    public long cleanup() {
        Long deleted = jdbc.queryForObject(
                "select identity_access.ia_cleanup_high_risk_expired(?)",
                Long.class, Timestamp.from(time.now()));
        if (deleted == null || deleted < 0) {
            throw new IllegalStateException("IDENTITY_HIGH_RISK_RETENTION_UNAVAILABLE");
        }
        return deleted;
    }
}
