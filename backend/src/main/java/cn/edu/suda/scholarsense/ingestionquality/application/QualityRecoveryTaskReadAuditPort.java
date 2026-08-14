package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.List;

@FunctionalInterface
public interface QualityRecoveryTaskReadAuditPort {
    void record(
            List<QualityRecoveryTask> tasks,
            QualitySnapshotActorContext actor,
            String action,
            String traceId);
}
