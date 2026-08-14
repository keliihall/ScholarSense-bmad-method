package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryConfirmationRelayProcessor;
import org.springframework.scheduling.annotation.Scheduled;

public final class QualityRecoveryConfirmationRelayScheduler {
    private final QualityRecoveryConfirmationRelayProcessor processor;

    public QualityRecoveryConfirmationRelayScheduler(
            QualityRecoveryConfirmationRelayProcessor processor) {
        this.processor = java.util.Objects.requireNonNull(processor);
    }

    @Scheduled(fixedDelayString =
            "${scholarsense.ingestion-quality.recovery-confirmation-delay-ms:5000}")
    public void relay() {
        processor.runBatch(100);
    }
}
