package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Instant;
import java.util.UUID;

public final class IdentitySyncJobService {
    private final IdentitySyncJobPort jobs;
    private final TrustedTimeSource time;

    public IdentitySyncJobService(IdentitySyncJobPort jobs, TrustedTimeSource time) {
        this.jobs = jobs;
        this.time = time;
    }

    public UUID request(CheckpointKey key, int retryBudget, String traceId) {
        Instant now = time.now().instant();
        UUID jobId = UUID.fromString(UuidV7.generate(now));
        jobs.enqueue(new IdentitySyncJob(
                jobId,
                key,
                IdentitySyncJobStatus.QUEUED,
                IdentitySourceHealth.DEGRADED,
                IdentityProjectionFreshness.STALE,
                now,
                null,
                jobs.lastSuccessfulWatermark(key),
                now,
                retryBudget,
                0,
                null,
                traceId));
        return jobId;
    }

    public java.util.Optional<UUID> ensureRequested(
            CheckpointKey key, int retryBudget, String traceId) {
        Instant now = time.now().instant();
        UUID jobId = UUID.fromString(UuidV7.generate(now));
        IdentitySyncJob candidate = new IdentitySyncJob(
                jobId,
                key,
                IdentitySyncJobStatus.QUEUED,
                IdentitySourceHealth.DEGRADED,
                IdentityProjectionFreshness.STALE,
                now,
                null,
                jobs.lastSuccessfulWatermark(key),
                now,
                retryBudget,
                0,
                null,
                traceId);
        return jobs.enqueueIfEligible(candidate)
                ? java.util.Optional.of(jobId)
                : java.util.Optional.empty();
    }

    /**
     * Explicit recovery path for a terminal failure. Automatic polling cannot
     * use this method because it requires an attributable operator decision.
     */
    public java.util.Optional<UUID> resolveFailureAndRequest(
            CheckpointKey key,
            int retryBudget,
            String resolvedBy,
            String resolutionCode,
            String traceId) {
        Instant now = time.now().instant();
        UUID jobId = UUID.fromString(UuidV7.generate(now));
        IdentitySyncJob replacement = new IdentitySyncJob(
                jobId,
                key,
                IdentitySyncJobStatus.QUEUED,
                IdentitySourceHealth.DEGRADED,
                IdentityProjectionFreshness.STALE,
                now,
                null,
                jobs.lastSuccessfulWatermark(key),
                now,
                retryBudget,
                0,
                null,
                traceId);
        IdentitySyncFailureResolution resolution =
                new IdentitySyncFailureResolution(
                        UUID.fromString(UuidV7.generate(now)),
                        key,
                        resolvedBy,
                        resolutionCode,
                        traceId,
                        now);
        return jobs.resolveFailureAndEnqueue(resolution, replacement)
                ? java.util.Optional.of(jobId)
                : java.util.Optional.empty();
    }
}
