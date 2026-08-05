package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.CatalogRetentionCleanupPort;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Worker-only RS-1.0.0 lifecycle caller. */
public final class CatalogRetentionScheduler {
    private final CatalogRetentionCleanupPort cleanup;

    public CatalogRetentionScheduler(CatalogRetentionCleanupPort cleanup) {
        this.cleanup = Objects.requireNonNull(cleanup);
    }

    @Scheduled(
            initialDelayString = "${scholarsense.ingestion-quality.retention.initial-delay:PT1M}",
            fixedDelayString = "${scholarsense.ingestion-quality.retention.interval:PT24H}")
    public void cleanupExpired() {
        cleanup.cleanupExpired();
    }
}
