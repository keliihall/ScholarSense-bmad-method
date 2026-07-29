package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.Objects;
import java.util.UUID;

public record IdentitySyncWorkerRun(
        UUID jobId,
        int attemptNo,
        IdentitySyncJobStatus status,
        String reasonCode,
        long lastSuccessfulWatermark) {
    public IdentitySyncWorkerRun {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(status, "status");
        if (attemptNo < 1 || lastSuccessfulWatermark < 0) {
            throw new IllegalArgumentException("IDENTITY_SYNC_WORKER_RESULT_INVALID");
        }
    }
}
