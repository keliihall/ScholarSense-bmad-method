package cn.edu.suda.scholarsense.auditoperations.adapters.inbound;

import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchAuditRelayProcessor;
import org.springframework.scheduling.annotation.Scheduled;

public final class AuditSearchRelayScheduler {
    private final AuditSearchAuditRelayProcessor processor;

    public AuditSearchRelayScheduler(AuditSearchAuditRelayProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(
            initialDelayString = "${scholarsense.audit.collector.initial-delay}",
            fixedDelayString = "${scholarsense.audit.collector.interval}")
    public void relay() {
        processor.runBatch();
    }
}
