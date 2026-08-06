package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditRelayProcessor;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Worker-only caller for the ingestion-quality producer outbox relay. */
public final class CatalogAuditRelayScheduler {
    private final CatalogAuditRelayProcessor processor;

    public CatalogAuditRelayScheduler(CatalogAuditRelayProcessor processor) {
        this.processor = Objects.requireNonNull(processor);
    }

    @Scheduled(
            initialDelayString = "${scholarsense.audit.collector.initial-delay}",
            fixedDelayString = "${scholarsense.audit.collector.interval}")
    public void relay() {
        processor.runBatch();
    }
}
