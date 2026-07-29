package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.UUID;

@FunctionalInterface
public interface IdentityLeasePort {
    IdentityLease acquire(
            CheckpointKey key,
            UUID jobId,
            int attemptNo,
            String leaseOwner,
            Instant now);
}
