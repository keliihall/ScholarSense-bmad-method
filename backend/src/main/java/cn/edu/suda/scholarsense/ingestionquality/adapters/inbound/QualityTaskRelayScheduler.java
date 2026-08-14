package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.QualityTaskRelayProcessor;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Worker-only trigger; each invocation claims at most one bounded delivery. */
public final class QualityTaskRelayScheduler {
    private final QualityTaskRelayProcessor processor;

    public QualityTaskRelayScheduler(QualityTaskRelayProcessor processor) {
        this.processor = Objects.requireNonNull(processor);
    }

    @Scheduled(
            initialDelayString =
                    "${scholarsense.ingestion-quality.task-relay-initial-delay:PT5S}",
            fixedDelayString =
                    "${scholarsense.ingestion-quality.task-relay-interval:PT5S}")
    public void relay() {
        processor.runOnce();
    }
}
