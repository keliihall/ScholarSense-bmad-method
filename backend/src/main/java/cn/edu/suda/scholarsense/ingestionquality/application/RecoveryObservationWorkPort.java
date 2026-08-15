package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationDecision;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Dedicated observation-job persistence boundary; no MappingRecomputeJob identity is shared. */
public interface RecoveryObservationWorkPort {
    List<RecoveryObservationCandidate> findClaimable(int limit, Instant trustedNow);

    RecoveryObservationClaim claim(
            UUID jobId, String workerDigest, Instant trustedNow, Duration leaseDuration);

    boolean isLeaseCurrent(UUID jobId, long leaseGeneration, Instant trustedNow);

    boolean complete(
            UUID jobId, long leaseGeneration,
            RecoveryObservationDecision decision, Instant trustedNow);

    boolean retry(
            UUID jobId, long leaseGeneration, Instant nextAttemptAt, Instant trustedNow);

    boolean yield(
            UUID jobId, long leaseGeneration, Instant nextAttemptAt, Instant trustedNow);

    boolean fail(UUID jobId, long leaseGeneration, Instant trustedNow);
}
