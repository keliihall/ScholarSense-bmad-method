package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.UUID;

/** Authorizes and audits the owner task before reading its observation projection. */
public final class RecoveryObservationQueryService {
    private final QualityRecoveryTaskQueryService tasks;
    private final RecoveryObservationQueryPort observations;

    public RecoveryObservationQueryService(
            QualityRecoveryTaskQueryService tasks,
            RecoveryObservationQueryPort observations) {
        this.tasks = java.util.Objects.requireNonNull(tasks);
        this.observations = java.util.Objects.requireNonNull(observations);
    }

    public RecoveryObservationView get(
            UUID taskId, QualitySnapshotActorContext actor, String traceId) {
        QualityRecoveryTaskView task = tasks.get(taskId, actor, traceId);
        RecoveryObservationView observation = observations.findByTaskId(taskId)
                .orElseThrow(RecoveryObservationQueryService::notFound);
        if (!task.taskId().equals(observation.taskId())
                || task.taskVersion() != observation.taskVersion()) throw notFound();
        return observation;
    }

    private static IngestionQualityApplicationException notFound() {
        return new IngestionQualityApplicationException("INGESTION_QUALITY_FORBIDDEN");
    }
}
