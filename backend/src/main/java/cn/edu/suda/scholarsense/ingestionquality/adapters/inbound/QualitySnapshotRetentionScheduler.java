package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionOrchestrator;
import cn.edu.suda.scholarsense.shared.observability.CurrentTraceSource;
import cn.edu.suda.scholarsense.shared.observability.ObservationPort;
import cn.edu.suda.scholarsense.shared.observability.SafeObservationAttributes;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContextCodec;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/** Production caller for authority-backed QualitySnapshot retention. */
public final class QualitySnapshotRetentionScheduler {
    private final QualitySnapshotRetentionOrchestrator orchestrator;
    private final CurrentTraceSource currentTrace;
    private final W3cTraceContextCodec traceCodec;
    private final ObservationPort observations;

    public QualitySnapshotRetentionScheduler(QualitySnapshotRetentionOrchestrator orchestrator) {
        this(orchestrator, java.util.Optional::empty, new W3cTraceContextCodec(), null);
    }

    public QualitySnapshotRetentionScheduler(
            QualitySnapshotRetentionOrchestrator orchestrator,
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec traceCodec) {
        this(orchestrator, currentTrace, traceCodec, null);
    }

    public QualitySnapshotRetentionScheduler(
            QualitySnapshotRetentionOrchestrator orchestrator,
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec traceCodec,
            ObservationPort observations) {
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.currentTrace = Objects.requireNonNull(currentTrace);
        this.traceCodec = Objects.requireNonNull(traceCodec);
        this.observations = observations;
    }

    @Scheduled(
            initialDelayString = "${scholarsense.ingestion-quality.retention."
                    + "quality-snapshot-initial-delay:PT1M}",
            fixedDelayString = "${scholarsense.ingestion-quality.retention."
                    + "quality-snapshot-interval:PT1H}")
    public void executeOne() {
        var parent = currentTrace.current().orElseGet(() -> traceCodec.newRoot(false));
        if (observations == null) {
            orchestrator.runOne(parent.traceId());
            return;
        }
        try (ObservationPort.ObservationScope attempt = observations.start(
                "job.attempt",
                ObservationPort.ObservationKind.INTERNAL,
                SafeObservationAttributes.create()
                        .low("module", "ingestion-quality")
                        .low("operation", "job.attempt"),
                parent)) {
            try {
                orchestrator.runOne(attempt.context().traceId());
                attempt.outcome("success");
            } catch (RuntimeException failure) {
                attempt.outcome("failure");
                attempt.error(failure);
                throw failure;
            }
        }
    }
}
