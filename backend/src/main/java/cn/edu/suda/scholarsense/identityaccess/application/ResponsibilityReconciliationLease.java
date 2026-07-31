package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ResponsibilityReconciliationLease(
        CheckpointKey key,
        LocalDate businessDate,
        UUID jobId,
        int attemptNo,
        long fencingToken,
        String leaseOwner,
        Instant acquiredAt,
        Instant expiresAt) {
    public ResponsibilityReconciliationLease {
        if (key == null
                || businessDate == null
                || jobId == null
                || jobId.version() != 7
                || attemptNo < 1
                || fencingToken < 1
                || leaseOwner == null
                || !leaseOwner.matches("[A-Za-z0-9._-]{3,128}")
                || acquiredAt == null
                || expiresAt == null
                || !expiresAt.isAfter(acquiredAt)) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_RECONCILIATION_LEASE_INVALID");
        }
    }
}
