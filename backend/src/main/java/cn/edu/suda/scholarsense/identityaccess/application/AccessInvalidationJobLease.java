package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationJob;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationJobState;
import java.time.Instant;
import java.util.UUID;

public record AccessInvalidationJobLease(
        AccessInvalidationJob job,
        UUID causeEventId,
        long attemptNo,
        Instant retainUntil,
        String cursorKey) {
    public AccessInvalidationJobLease(
            AccessInvalidationJob job,
            UUID causeEventId,
            long attemptNo,
            Instant retainUntil) {
        this(job, causeEventId, attemptNo, retainUntil, null);
    }

    public AccessInvalidationJobLease {
        if (job == null
                || job.state() != AccessInvalidationJobState.RUNNING
                || attemptNo < 1
                || retainUntil == null
                || !retainUntil.isAfter(job.updatedAt())
                || causeEventId == null
                || (cursorKey != null
                        && !cursorKey.matches(
                                "lin_[A-Za-z0-9_-]{32,128}"))) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_JOB_LEASE_INVALID");
        }
    }
}
