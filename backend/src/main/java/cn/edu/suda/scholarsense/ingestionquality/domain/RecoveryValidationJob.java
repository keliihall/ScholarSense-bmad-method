package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Recovery-specific durable validation state machine; it creates no business object. */
public final class RecoveryValidationJob {
    public static final int MAXIMUM_ATTEMPTS = 5;

    private final UUID jobId;
    private final RecoveryValidationJobBinding binding;
    private final Instant createdAt;
    private long jobVersion;
    private RecoveryValidationJobStatus status;
    private int attemptCount;
    private long leaseGeneration;
    private String leaseOwnerDigest;
    private Instant claimedAt;
    private Instant leaseExpiresAt;
    private long checkpointVersion;
    private RecoveryValidationCheckpoint checkpoint;
    private String resultDigest;
    private RecoveryValidationErrorCode errorCode;
    private Instant nextAttemptAt;
    private Instant updatedAt;
    private Instant completedAt;

    private RecoveryValidationJob(
            UUID jobId, RecoveryValidationJobBinding binding, Instant createdAt) {
        this.jobId = IngestionQualityDomainRules.requireUuidV7(jobId);
        if (binding == null || createdAt == null) throw IngestionQualityDomainRules.invalid();
        this.binding = binding;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.jobVersion = 1;
        this.status = RecoveryValidationJobStatus.QUEUED;
    }

    public static RecoveryValidationJob queued(
            UUID jobId, RecoveryValidationJobBinding binding, Instant createdAt) {
        return new RecoveryValidationJob(jobId, binding, createdAt);
    }

    public static RecoveryValidationJob restore(RecoveryValidationJobSnapshot snapshot) {
        if (snapshot == null) throw IngestionQualityDomainRules.invalid();
        RecoveryValidationJob job = new RecoveryValidationJob(
                snapshot.jobId(), snapshot.binding(), snapshot.createdAt());
        if (snapshot.jobVersion() < 1 || snapshot.status() == null
                || snapshot.attemptCount() < 0
                || snapshot.attemptCount() > MAXIMUM_ATTEMPTS
                || snapshot.leaseGeneration() < 0 || snapshot.checkpointVersion() < 0
                || snapshot.updatedAt() == null
                || snapshot.updatedAt().isBefore(snapshot.createdAt())
                || ((snapshot.status() == RecoveryValidationJobStatus.RUNNING)
                    != (snapshot.leaseOwnerDigest() != null && snapshot.claimedAt() != null
                        && snapshot.leaseExpiresAt() != null))
                || ((snapshot.checkpoint() == null) != (snapshot.checkpointVersion() == 0))
                || (snapshot.checkpoint() != null
                    && snapshot.checkpoint().checkpointVersion() != snapshot.checkpointVersion())
                || !validStateShape(snapshot)) {
            throw IngestionQualityDomainRules.invalid();
        }
        if (snapshot.leaseOwnerDigest() != null) {
            new RecoveryValidationAttempt(
                    snapshot.attemptCount(), snapshot.leaseGeneration(),
                    snapshot.leaseOwnerDigest(), snapshot.claimedAt(), snapshot.leaseExpiresAt());
        }
        if (snapshot.resultDigest() != null) {
            IngestionQualityDomainRules.requireSha256(snapshot.resultDigest());
        }
        job.jobVersion = snapshot.jobVersion();
        job.status = snapshot.status();
        job.attemptCount = snapshot.attemptCount();
        job.leaseGeneration = snapshot.leaseGeneration();
        job.leaseOwnerDigest = snapshot.leaseOwnerDigest();
        job.claimedAt = snapshot.claimedAt();
        job.leaseExpiresAt = snapshot.leaseExpiresAt();
        job.checkpointVersion = snapshot.checkpointVersion();
        job.checkpoint = snapshot.checkpoint();
        job.resultDigest = snapshot.resultDigest();
        job.errorCode = snapshot.errorCode();
        job.nextAttemptAt = snapshot.nextAttemptAt();
        job.updatedAt = snapshot.updatedAt();
        job.completedAt = snapshot.completedAt();
        return job;
    }

    public RecoveryValidationClaim claim(
            String workerDigest, Instant trustedNow, Duration leaseDuration) {
        IngestionQualityDomainRules.requireSha256(workerDigest);
        if (trustedNow == null || leaseDuration == null
                || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw IngestionQualityDomainRules.invalid();
        }
        boolean expired = status == RecoveryValidationJobStatus.RUNNING
                && leaseExpiresAt != null && !trustedNow.isBefore(leaseExpiresAt);
        boolean continuation = status == RecoveryValidationJobStatus.QUEUED
                && attemptCount > 0 && errorCode == null && nextAttemptAt != null;
        boolean incrementsAttempt = !continuation;
        if ((status != RecoveryValidationJobStatus.QUEUED && !expired)
                || incrementsAttempt && attemptCount >= MAXIMUM_ATTEMPTS
                || nextAttemptAt != null && trustedNow.isBefore(nextAttemptAt)
                || leaseGeneration >= IngestionQualityDomainRules.MAX_SAFE_VERSION) {
            throw invalidState();
        }
        if (incrementsAttempt) attemptCount++;
        leaseGeneration++;
        status = RecoveryValidationJobStatus.RUNNING;
        leaseOwnerDigest = workerDigest;
        claimedAt = trustedNow;
        try {
            leaseExpiresAt = trustedNow.plus(leaseDuration);
        } catch (RuntimeException overflow) {
            throw IngestionQualityDomainRules.invalid();
        }
        errorCode = null;
        nextAttemptAt = null;
        completedAt = null;
        changed(trustedNow);
        return new RecoveryValidationClaim(
                jobId, attemptCount, leaseGeneration, workerDigest,
                trustedNow, leaseExpiresAt, checkpointVersion, checkpoint);
    }

    public void checkpoint(
            long generation, RecoveryValidationCheckpoint next, Instant trustedNow) {
        requireActiveLease(generation, trustedNow);
        if (next == null || next.checkpointVersion() != checkpointVersion + 1) {
            throw invalidState();
        }
        if (checkpoint == null) {
            if (next.phase() != RecoveryValidationPhase.BACKFILL) throw invalidState();
        } else {
            int currentPhase = RecoveryValidationCheckpoint.phaseOrdinal(checkpoint.phase());
            int nextPhase = RecoveryValidationCheckpoint.phaseOrdinal(next.phase());
            if (nextPhase == currentPhase) {
                if (checkpoint.phaseCompleted()
                        || next.processedCount() < checkpoint.processedCount()) {
                    throw invalidState();
                }
            } else if (nextPhase != currentPhase + 1 || !checkpoint.phaseCompleted()) {
                throw invalidState();
            }
        }
        checkpoint = next;
        checkpointVersion = next.checkpointVersion();
        changed(trustedNow);
    }

    public void complete(
            long generation, RecoveryValidationResult result, Instant trustedNow) {
        requireActiveLease(generation, trustedNow);
        if (result == null) throw IngestionQualityDomainRules.invalid();
        requireResultBinding(result, RecoveryValidationJobStatus.SUCCEEDED);
        status = RecoveryValidationJobStatus.SUCCEEDED;
        resultDigest = result.resultDigest();
        errorCode = null;
        releaseLease();
        completedAt = trustedNow;
        changed(trustedNow);
    }

    public void fail(
            long generation,
            RecoveryValidationErrorCode failure,
            RecoveryValidationResult result,
            Instant trustedNow) {
        requireActiveLease(generation, trustedNow);
        if (failure == null || failure == RecoveryValidationErrorCode.CANCELLED
                || failure == RecoveryValidationErrorCode.STALE_FENCE
                || failure == RecoveryValidationErrorCode.LEASE_EXPIRED) {
            throw IngestionQualityDomainRules.invalid();
        }
        if (result == null) throw IngestionQualityDomainRules.invalid();
        requireResultBinding(result, RecoveryValidationJobStatus.FAILED);
        status = RecoveryValidationJobStatus.FAILED;
        errorCode = attemptCount >= MAXIMUM_ATTEMPTS
                ? RecoveryValidationErrorCode.RETRY_EXHAUSTED : failure;
        resultDigest = result.resultDigest();
        releaseLease();
        completedAt = trustedNow;
        changed(trustedNow);
    }

    public void retry(
            long generation,
            RecoveryValidationErrorCode failure,
            Instant nextAttempt,
            Instant trustedNow) {
        requireActiveLease(generation, trustedNow);
        if (failure == null || failure == RecoveryValidationErrorCode.PROVIDER_NOT_INSTALLED
                || failure == RecoveryValidationErrorCode.CANCELLED
                || attemptCount >= MAXIMUM_ATTEMPTS || nextAttempt == null
                || nextAttempt.isBefore(trustedNow)) {
            throw invalidState();
        }
        status = RecoveryValidationJobStatus.QUEUED;
        errorCode = failure;
        nextAttemptAt = nextAttempt;
        releaseLease();
        completedAt = null;
        changed(trustedNow);
    }

    public void yield(long generation, Instant nextAttempt, Instant trustedNow) {
        requireActiveLease(generation, trustedNow);
        if (nextAttempt == null || nextAttempt.isBefore(trustedNow)) throw invalidState();
        status = RecoveryValidationJobStatus.QUEUED;
        errorCode = null;
        nextAttemptAt = nextAttempt;
        releaseLease();
        changed(trustedNow);
    }

    public void cancel(RecoveryValidationResult result, Instant trustedNow) {
        if ((status != RecoveryValidationJobStatus.QUEUED
                && status != RecoveryValidationJobStatus.RUNNING) || trustedNow == null) {
            throw invalidState();
        }
        if (result == null) throw IngestionQualityDomainRules.invalid();
        requireResultBinding(result, RecoveryValidationJobStatus.CANCELLED);
        status = RecoveryValidationJobStatus.CANCELLED;
        errorCode = RecoveryValidationErrorCode.CANCELLED;
        resultDigest = result.resultDigest();
        nextAttemptAt = null;
        if (leaseGeneration >= IngestionQualityDomainRules.MAX_SAFE_VERSION) {
            throw invalidState();
        }
        leaseGeneration++;
        releaseLease();
        completedAt = trustedNow;
        changed(trustedNow);
    }

    private void requireActiveLease(long generation, Instant trustedNow) {
        if (status != RecoveryValidationJobStatus.RUNNING
                || generation != leaseGeneration) {
            throw new IngestionQualityException(
                    IngestionQualityErrorCode.INGESTION_QUALITY_STALE_FENCING_TOKEN);
        }
        if (trustedNow == null || leaseExpiresAt == null
                || !trustedNow.isBefore(leaseExpiresAt)) {
            throw new IngestionQualityException(
                    IngestionQualityErrorCode.INGESTION_QUALITY_LEASE_EXPIRED);
        }
    }

    private void changed(Instant trustedNow) {
        if (trustedNow == null || trustedNow.isBefore(updatedAt)
                || jobVersion >= IngestionQualityDomainRules.MAX_SAFE_VERSION) {
            throw invalidState();
        }
        updatedAt = trustedNow;
        jobVersion++;
    }

    private void releaseLease() {
        leaseOwnerDigest = null;
        claimedAt = null;
        leaseExpiresAt = null;
    }

    private void requireResultBinding(
            RecoveryValidationResult result, RecoveryValidationJobStatus expectedState) {
        if (!jobId.equals(result.jobId()) || result.jobVersion() != jobVersion
                || !binding.recoveryRequestId().equals(result.recoveryRequestId())
                || !binding.episodeId().equals(result.episodeId())
                || !binding.taskId().equals(result.taskId())
                || !binding.inputDigest().equals(result.inputDigest())
                || !binding.qualityRecoveryPolicyVersion()
                        .equals(result.qualityRecoveryPolicyVersion())
                || !binding.qualityRecoveryPolicyDigest()
                        .equals(result.qualityRecoveryPolicyDigest())
                || !binding.traceId().equals(result.traceId())
                || result.state() != expectedState) {
            throw IngestionQualityDomainRules.invalid();
        }
    }

    private static boolean validStateShape(RecoveryValidationJobSnapshot value) {
        boolean terminal = value.status().terminal();
        if (terminal != (value.completedAt() != null && value.resultDigest() != null)) {
            return false;
        }
        if (terminal && value.nextAttemptAt() != null) return false;
        if (value.status() == RecoveryValidationJobStatus.SUCCEEDED) {
            return value.errorCode() == null;
        }
        if (value.status() == RecoveryValidationJobStatus.FAILED) {
            return value.errorCode() != null
                    && value.errorCode() != RecoveryValidationErrorCode.CANCELLED;
        }
        if (value.status() == RecoveryValidationJobStatus.CANCELLED) {
            return value.errorCode() == RecoveryValidationErrorCode.CANCELLED;
        }
        return value.resultDigest() == null && value.completedAt() == null
                && (value.status() != RecoveryValidationJobStatus.RUNNING
                    || value.errorCode() == null)
                && (value.nextAttemptAt() == null
                    || value.status() == RecoveryValidationJobStatus.QUEUED);
    }

    private static IngestionQualityException invalidState() {
        return new IngestionQualityException(
                IngestionQualityErrorCode.INGESTION_QUALITY_INVALID_STATE);
    }

    public boolean canRetry() {
        return status == RecoveryValidationJobStatus.QUEUED
                && nextAttemptAt != null && attemptCount < MAXIMUM_ATTEMPTS;
    }

    public RecoveryValidationAttempt currentAttempt() {
        if (status != RecoveryValidationJobStatus.RUNNING) return null;
        return new RecoveryValidationAttempt(
                attemptCount, leaseGeneration, leaseOwnerDigest, claimedAt, leaseExpiresAt);
    }

    public RecoveryValidationJobSnapshot snapshot() {
        return new RecoveryValidationJobSnapshot(
                jobId, jobVersion, binding, status, attemptCount, leaseGeneration,
                leaseOwnerDigest, claimedAt, leaseExpiresAt, checkpointVersion,
                checkpoint, resultDigest, errorCode, nextAttemptAt,
                createdAt, updatedAt, completedAt);
    }

    public UUID jobId() { return jobId; }
    public RecoveryValidationJobBinding binding() { return binding; }
    public RecoveryValidationJobStatus status() { return status; }
    public int attemptCount() { return attemptCount; }
    public long leaseGeneration() { return leaseGeneration; }
    public String leaseOwnerDigest() { return leaseOwnerDigest; }
    public long checkpointVersion() { return checkpointVersion; }
    public RecoveryValidationCheckpoint checkpoint() { return checkpoint; }
    public String resultDigest() { return resultDigest; }
    public RecoveryValidationErrorCode errorCode() { return errorCode; }
    public Instant nextAttemptAt() { return nextAttemptAt; }
    public Instant completedAt() { return completedAt; }
}
