package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Objects;

public record RunningIdentitySyncAttempt(
        IdentitySyncJob job,
        int attemptNo,
        IdentityLease lease,
        long inputWatermark,
        Instant startedAt) {
    public RunningIdentitySyncAttempt {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(startedAt, "startedAt");
        if (job.status() != IdentitySyncJobStatus.RUNNING
                || attemptNo < 1
                || inputWatermark < 0
                || attemptNo != lease.attemptNo()
                || !job.jobId().equals(lease.jobId())
                || !job.key().equals(lease.key())) {
            throw new IllegalArgumentException("IDENTITY_SYNC_ATTEMPT_INVALID");
        }
    }
}
