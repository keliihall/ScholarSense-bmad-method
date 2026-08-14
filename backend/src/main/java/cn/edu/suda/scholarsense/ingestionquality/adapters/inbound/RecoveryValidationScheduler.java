package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryValidationJobProcessor;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded durable validation poller; processor-level fences make concurrent schedulers safe. */
public final class RecoveryValidationScheduler {
    private final RecoveryValidationJobProcessor processor;

    public RecoveryValidationScheduler(RecoveryValidationJobProcessor processor) {
        this.processor = java.util.Objects.requireNonNull(processor);
    }

    @Scheduled(
            fixedDelayString = "${scholarsense.ingestion-quality.recovery-worker-delay-ms:1000}")
    public void run() {
        processor.runBatch();
    }
}
