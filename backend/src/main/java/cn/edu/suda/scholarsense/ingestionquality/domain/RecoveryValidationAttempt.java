package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;

/** Current durable attempt; the lease generation is its fencing token. */
public record RecoveryValidationAttempt(
        int attemptNumber,
        long leaseGeneration,
        String leaseOwnerDigest,
        Instant claimedAt,
        Instant leaseExpiresAt) {

    public RecoveryValidationAttempt {
        if (attemptNumber < 1 || attemptNumber > RecoveryValidationJob.MAXIMUM_ATTEMPTS
                || leaseGeneration < 1 || claimedAt == null || leaseExpiresAt == null
                || !leaseExpiresAt.isAfter(claimedAt)) {
            throw IngestionQualityDomainRules.invalid();
        }
        IngestionQualityDomainRules.requireSha256(leaseOwnerDigest);
    }
}
