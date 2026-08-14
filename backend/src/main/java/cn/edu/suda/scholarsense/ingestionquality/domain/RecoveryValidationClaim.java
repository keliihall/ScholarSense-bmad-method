package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.UUID;

public record RecoveryValidationClaim(
        UUID jobId,
        int attemptNumber,
        long leaseGeneration,
        String leaseOwnerDigest,
        Instant claimedAt,
        Instant leaseExpiresAt,
        long checkpointVersion,
        RecoveryValidationCheckpoint checkpoint) {

    public RecoveryValidationClaim {
        IngestionQualityDomainRules.requireUuidV7(jobId);
        new RecoveryValidationAttempt(
                attemptNumber, leaseGeneration, leaseOwnerDigest, claimedAt, leaseExpiresAt);
        if (checkpointVersion < 0
                || checkpointVersion > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || (checkpoint == null) != (checkpointVersion == 0)
                || checkpoint != null && checkpoint.checkpointVersion() != checkpointVersion) {
            throw IngestionQualityDomainRules.invalid();
        }
    }
}
