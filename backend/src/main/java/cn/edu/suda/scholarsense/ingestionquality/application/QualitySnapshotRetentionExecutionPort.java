package cn.edu.suda.scholarsense.ingestionquality.application;

/** Calls the owner retention boundary through the dedicated executor workload connection only. */
@FunctionalInterface
public interface QualitySnapshotRetentionExecutionPort {
    QualitySnapshotRetentionResult execute(
            QualitySnapshotRetentionCandidate candidate,
            QualitySnapshotRetentionAttemptIds attemptIds,
            String requestedTraceId);
}
