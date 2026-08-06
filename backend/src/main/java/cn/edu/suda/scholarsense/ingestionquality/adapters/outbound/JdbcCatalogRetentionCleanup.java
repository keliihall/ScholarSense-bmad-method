package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.CatalogRetentionCleanupPort;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.sql.Timestamp;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Calls only the owner SECURITY DEFINER cleanup function using a fresh trusted-time sample. */
public final class JdbcCatalogRetentionCleanup implements CatalogRetentionCleanupPort {
    private final JdbcTemplate jdbc;
    private final TrustedTimeSource time;

    public JdbcCatalogRetentionCleanup(JdbcTemplate jdbc, TrustedTimeSource time) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.time = Objects.requireNonNull(time);
    }

    @Override
    public long cleanupExpired() {
        TrustedTime cutoff;
        try {
            cutoff = Objects.requireNonNull(time.now(), "trusted time");
        } catch (RuntimeException unavailable) {
            throw new IngestionQualityApplicationException(
                    "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", unavailable);
        }
        try {
            Long deleted = jdbc.queryForObject(
                    "select ingestion_quality.iq_cleanup_expired(?)",
                    Long.class,
                    Timestamp.from(cutoff.instant()));
            if (deleted == null || deleted < 0) {
                throw new IngestionQualityApplicationException(
                        "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
            }
            return deleted;
        } catch (DataAccessException unavailable) {
            RuntimeException translated = CatalogJdbcFailures.translate(unavailable);
            if (translated instanceof IngestionQualityApplicationException classified) {
                throw classified;
            }
            throw new IngestionQualityApplicationException(
                    "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", unavailable);
        }
    }
}
