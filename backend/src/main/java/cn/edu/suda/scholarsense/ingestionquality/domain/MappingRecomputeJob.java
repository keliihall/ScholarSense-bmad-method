package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class MappingRecomputeJob {
    private final UUID jobId;
    private final String ownerSourceId;
    private final MappingRecomputeIdentity identity;
    private final Instant latestActionableAt;
    private final Instant queuedAt;
    private final String traceId;
    private MappingRecomputeJobStatus status;
    private int attemptNo;
    private long fencingToken;
    private String leaseOwner;
    private Instant leaseUntil;
    private long checkpoint;
    private boolean historyCorrected;
    private boolean businessPublicationCreated;
    private MappingRecomputeResultCode resultCode;
    private String failureCode;
    private Instant completedAt;

    private MappingRecomputeJob(
            UUID jobId, String ownerSourceId, MappingRecomputeIdentity identity,
            Instant latestActionableAt,
            Instant queuedAt, String traceId) {
        this.jobId = IngestionQualityDomainRules.requireUuidV7(jobId);
        if (ownerSourceId == null
                || !ownerSourceId.matches("^SRC-P[01]-[A-Z-]+-[0-9]{3}$")) {
            throw IngestionQualityDomainRules.invalid();
        }
        this.ownerSourceId = ownerSourceId;
        if (identity == null || latestActionableAt == null || queuedAt == null
                || traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw IngestionQualityDomainRules.invalid();
        }
        this.identity = identity;
        this.latestActionableAt = latestActionableAt;
        this.queuedAt = queuedAt;
        this.traceId = traceId;
        this.status = MappingRecomputeJobStatus.QUEUED;
    }

    public static MappingRecomputeJob queued(
            UUID jobId, String ownerSourceId, MappingRecomputeIdentity identity,
            Instant latestActionableAt,
            Instant queuedAt, String traceId) {
        return new MappingRecomputeJob(
                jobId, ownerSourceId, identity, latestActionableAt, queuedAt, traceId);
    }

    public static MappingRecomputeJob restore(
            UUID jobId, String ownerSourceId, MappingRecomputeIdentity identity,
            Instant latestActionableAt,
            Instant queuedAt, String traceId, MappingRecomputeJobStatus status,
            int attemptNo, long fencingToken, String leaseOwner, Instant leaseUntil,
            long checkpoint, boolean historyCorrected, boolean businessPublicationCreated,
            MappingRecomputeResultCode resultCode, String failureCode, Instant completedAt) {
        MappingRecomputeJob job = new MappingRecomputeJob(
                jobId, ownerSourceId, identity, latestActionableAt, queuedAt, traceId);
        if (status == null || attemptNo < 0 || fencingToken < 0 || checkpoint < 0
                || ((status == MappingRecomputeJobStatus.RUNNING)
                    != (leaseOwner != null && leaseUntil != null))) {
            throw IngestionQualityDomainRules.invalid();
        }
        job.status = status;
        job.attemptNo = attemptNo;
        job.fencingToken = fencingToken;
        job.leaseOwner = leaseOwner;
        job.leaseUntil = leaseUntil;
        job.checkpoint = checkpoint;
        job.historyCorrected = historyCorrected;
        job.businessPublicationCreated = businessPublicationCreated;
        job.resultCode = resultCode;
        job.failureCode = failureCode;
        job.completedAt = completedAt;
        return job;
    }

    public long claim(String workerId, Instant serverNow, Duration leaseDuration) {
        IngestionQualityDomainRules.requireText(workerId, 128);
        if (serverNow == null || leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw IngestionQualityDomainRules.invalid();
        }
        boolean expiredLease = status == MappingRecomputeJobStatus.RUNNING
                && leaseUntil != null && !serverNow.isBefore(leaseUntil);
        if (status != MappingRecomputeJobStatus.QUEUED && !expiredLease) {
            throw invalidState();
        }
        if (fencingToken == Long.MAX_VALUE || attemptNo == Integer.MAX_VALUE) {
            throw invalidState();
        }
        status = MappingRecomputeJobStatus.RUNNING;
        attemptNo++;
        fencingToken++;
        leaseOwner = workerId;
        leaseUntil = serverNow.plus(leaseDuration);
        failureCode = null;
        return fencingToken;
    }

    public void checkpoint(long token, long sequence, Instant serverNow) {
        requireActiveFence(token, serverNow);
        if (sequence <= checkpoint) {
            throw invalidState();
        }
        checkpoint = sequence;
    }

    public void fail(long token, String controlledFailureCode, Instant serverNow) {
        requireActiveFence(token, serverNow);
        failureCode = IngestionQualityDomainRules.requireText(controlledFailureCode, 64);
        status = MappingRecomputeJobStatus.FAILED;
        leaseOwner = null;
        leaseUntil = null;
        completedAt = serverNow;
    }

    public void requeue(Instant serverNow) {
        if (status != MappingRecomputeJobStatus.FAILED || serverNow == null) {
            throw invalidState();
        }
        status = MappingRecomputeJobStatus.QUEUED;
        completedAt = null;
    }

    public void cancel(Instant serverNow) {
        if ((status != MappingRecomputeJobStatus.QUEUED
                && status != MappingRecomputeJobStatus.RUNNING) || serverNow == null) {
            throw invalidState();
        }
        status = MappingRecomputeJobStatus.CANCELLED;
        leaseOwner = null;
        leaseUntil = null;
        completedAt = serverNow;
    }

    public MappingRecomputeResultCode complete(long token, Instant serverNow) {
        requireActiveFence(token, serverNow);
        historyCorrected = true;
        businessPublicationCreated = serverNow.isBefore(latestActionableAt);
        resultCode = businessPublicationCreated
                ? MappingRecomputeResultCode.RECOMPUTED
                : MappingRecomputeResultCode.EXPIRED_HISTORY_ONLY;
        status = MappingRecomputeJobStatus.SUCCEEDED;
        leaseOwner = null;
        leaseUntil = null;
        completedAt = serverNow;
        return resultCode;
    }

    private void requireActiveFence(long token, Instant serverNow) {
        if (status != MappingRecomputeJobStatus.RUNNING || token != fencingToken) {
            throw new IngestionQualityException(
                    IngestionQualityErrorCode.INGESTION_QUALITY_STALE_FENCING_TOKEN);
        }
        if (serverNow == null || leaseUntil == null || !serverNow.isBefore(leaseUntil)) {
            throw new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_LEASE_EXPIRED);
        }
    }

    private static IngestionQualityException invalidState() {
        return new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_INVALID_STATE);
    }

    public UUID jobId() { return jobId; }
    public String ownerSourceId() { return ownerSourceId; }
    public MappingRecomputeIdentity identity() { return identity; }
    public Instant latestActionableAt() { return latestActionableAt; }
    public Instant queuedAt() { return queuedAt; }
    public String traceId() { return traceId; }
    public MappingRecomputeJobStatus status() { return status; }
    public int attemptNo() { return attemptNo; }
    public long fencingToken() { return fencingToken; }
    public String leaseOwner() { return leaseOwner; }
    public Instant leaseUntil() { return leaseUntil; }
    public long checkpoint() { return checkpoint; }
    public boolean historyCorrected() { return historyCorrected; }
    public boolean businessPublicationCreated() { return businessPublicationCreated; }
    public MappingRecomputeResultCode resultCode() { return resultCode; }
    public String failureCode() { return failureCode; }
    public Instant completedAt() { return completedAt; }
}
