package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.SubjectWindowRecomputeProcessor;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Polls the durable job queue; state and retry ownership remain in PostgreSQL. */
public final class SubjectWindowRecomputeScheduler {
    private final SubjectWindowRecomputeProcessor processor;

    public SubjectWindowRecomputeScheduler(SubjectWindowRecomputeProcessor processor) {
        this.processor = Objects.requireNonNull(processor);
    }

    @Scheduled(
            initialDelayString =
                    "${scholarsense.ingestion-quality.subject-recompute-initial-delay:PT5S}",
            fixedDelayString =
                    "${scholarsense.ingestion-quality.subject-recompute-interval:PT5S}")
    public void poll() {
        processor.runBatch();
    }
}
