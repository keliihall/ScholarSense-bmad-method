package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionOrchestrator;
import cn.edu.suda.scholarsense.shared.trace.W3cTraceId;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Production caller for authority-backed QualitySnapshot retention. */
public final class QualitySnapshotRetentionScheduler {
    private final QualitySnapshotRetentionOrchestrator orchestrator;

    public QualitySnapshotRetentionScheduler(QualitySnapshotRetentionOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator);
    }

    @Scheduled(
            initialDelayString = "${scholarsense.ingestion-quality.retention."
                    + "quality-snapshot-initial-delay:PT1M}",
            fixedDelayString = "${scholarsense.ingestion-quality.retention."
                    + "quality-snapshot-interval:PT1H}")
    public void executeOne() {
        orchestrator.runOne(W3cTraceId.from(null, "scheduled:quality-snapshot-retention"));
    }
}
