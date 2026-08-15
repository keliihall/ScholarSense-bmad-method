package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryObservationJobProcessor;
import org.springframework.scheduling.annotation.Scheduled;

/** Dedicated bounded observation poller; it shares no MappingRecomputeJob identity. */
public final class RecoveryObservationScheduler {
    private final RecoveryObservationJobProcessor processor;

    public RecoveryObservationScheduler(RecoveryObservationJobProcessor processor) {
        this.processor = java.util.Objects.requireNonNull(processor);
    }

    @Scheduled(fixedDelayString =
            "${scholarsense.ingestion-quality.recovery-observation-worker-delay-ms:1000}")
    public void run() {
        processor.runBatch();
    }
}
