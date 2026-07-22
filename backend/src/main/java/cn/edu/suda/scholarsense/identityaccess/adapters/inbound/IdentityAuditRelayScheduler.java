package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.AuditOutboxRelayProcessor;
import org.springframework.scheduling.annotation.Scheduled;

public final class IdentityAuditRelayScheduler {
    private final AuditOutboxRelayProcessor processor;

    public IdentityAuditRelayScheduler(AuditOutboxRelayProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(
            initialDelayString = "${scholarsense.audit.collector.initial-delay}",
            fixedDelayString = "${scholarsense.audit.collector.interval}")
    public void relay() {
        processor.runBatch();
    }
}
