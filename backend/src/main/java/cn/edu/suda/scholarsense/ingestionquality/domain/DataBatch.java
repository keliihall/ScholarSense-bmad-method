package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class DataBatch {
    public static final long MAX_VERSION = IngestionQualityDomainRules.MAX_SAFE_VERSION;
    private final UUID batchId;
    private final BatchIdentity identity;
    private final BatchLineage lineage;
    private final String declaredManifestDigest;
    private final String traceId;
    private final DataBatchStatus status;
    private final long aggregateVersion;
    private final BatchManifest manifest;
    private final SealedQualityContractEvidence sealedQualityContractEvidence;
    private final Instant receivedAt;
    private final Instant sealedAt;
    private final Instant evaluatedAt;
    private final Instant publishedAt;

    private DataBatch(
            UUID batchId,
            BatchIdentity identity,
            BatchLineage lineage,
            String declaredManifestDigest,
            String traceId,
            DataBatchStatus status,
            long aggregateVersion,
            BatchManifest manifest,
            SealedQualityContractEvidence sealedQualityContractEvidence,
            Instant receivedAt,
            Instant sealedAt,
            Instant evaluatedAt,
            Instant publishedAt) {
        this.batchId = IngestionQualityDomainRules.requireUuidV7(batchId);
        this.identity = Objects.requireNonNull(identity);
        this.lineage = Objects.requireNonNull(lineage);
        this.declaredManifestDigest = IngestionQualityDomainRules.requireSha256(
                declaredManifestDigest);
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")
                || traceId.matches("0{32}")) {
            throw IngestionQualityDomainRules.invalid();
        }
        this.traceId = traceId;
        this.status = Objects.requireNonNull(status);
        this.aggregateVersion = IngestionQualityDomainRules.requireVersion(aggregateVersion);
        this.manifest = manifest;
        this.sealedQualityContractEvidence = sealedQualityContractEvidence;
        this.receivedAt = Objects.requireNonNull(receivedAt);
        this.sealedAt = sealedAt;
        this.evaluatedAt = evaluatedAt;
        this.publishedAt = publishedAt;
        requireShape();
    }

    public static DataBatch receiving(
            UUID batchId,
            BatchIdentity identity,
            BatchLineage lineage,
            String declaredManifestDigest,
            Instant receivedAt,
            String traceId) {
        return new DataBatch(
                batchId, identity, lineage, declaredManifestDigest, traceId,
                DataBatchStatus.RECEIVING, 1, null, null, receivedAt, null, null, null);
    }

    /** Restores the complete owner-persisted aggregate without replaying lifecycle transitions. */
    public static DataBatch restore(
            UUID batchId,
            BatchIdentity identity,
            BatchLineage lineage,
            String declaredManifestDigest,
            String traceId,
            DataBatchStatus status,
            long aggregateVersion,
            BatchManifest manifest,
            SealedQualityContractEvidence sealedQualityContractEvidence,
            Instant receivedAt,
            Instant sealedAt,
            Instant evaluatedAt,
            Instant publishedAt) {
        return new DataBatch(
                batchId, identity, lineage, declaredManifestDigest, traceId, status,
                aggregateVersion, manifest, sealedQualityContractEvidence, receivedAt,
                sealedAt, evaluatedAt, publishedAt);
    }

    public DataBatch seal(
            BatchManifest sealedManifest,
            SealedQualityContractEvidence contractEvidence,
            Instant at) {
        if (status != DataBatchStatus.RECEIVING) {
            throw invalidState();
        }
        Objects.requireNonNull(sealedManifest);
        Objects.requireNonNull(contractEvidence);
        if (!declaredManifestDigest.equals(sealedManifest.manifestDigest())) {
            throw IngestionQualityDomainRules.invalid();
        }
        if (!receivedAt.equals(sealedManifest.receivedAt()) || at == null || at.isBefore(receivedAt)) {
            throw IngestionQualityDomainRules.invalid();
        }
        IngestionQualityDomainRules.requireProductionWatermark(
                identity.sourceId(), sealedManifest.watermark());
        return copy(
                DataBatchStatus.SEALED, nextVersion(), sealedManifest,
                contractEvidence, at, null, null);
    }

    public DataBatch recordQualityResult(boolean passed, Instant at) {
        if (status != DataBatchStatus.SEALED) {
            throw invalidState();
        }
        if (at == null || at.isBefore(sealedAt)) {
            throw IngestionQualityDomainRules.invalid();
        }
        return copy(
                passed ? DataBatchStatus.QUALITY_PASSED : DataBatchStatus.QUALITY_FAILED,
                nextVersion(), manifest, sealedQualityContractEvidence, sealedAt, at, null);
    }

    public DataBatch publish(Instant at) {
        if (status != DataBatchStatus.QUALITY_PASSED) {
            throw invalidState();
        }
        if (at == null || at.isBefore(evaluatedAt)) {
            throw IngestionQualityDomainRules.invalid();
        }
        return copy(
                DataBatchStatus.PUBLISHED, nextVersion(), manifest,
                sealedQualityContractEvidence, sealedAt, evaluatedAt, at);
    }

    private DataBatch copy(
            DataBatchStatus nextStatus,
            long nextVersion,
            BatchManifest nextManifest,
            SealedQualityContractEvidence nextContractEvidence,
            Instant nextSealedAt,
            Instant nextEvaluatedAt,
            Instant nextPublishedAt) {
        return new DataBatch(
                batchId, identity, lineage, declaredManifestDigest, traceId,
                nextStatus, nextVersion, nextManifest, nextContractEvidence, receivedAt,
                nextSealedAt, nextEvaluatedAt, nextPublishedAt);
    }

    private long nextVersion() {
        if (aggregateVersion == IngestionQualityDomainRules.MAX_SAFE_VERSION) {
            throw new IngestionQualityException(
                    IngestionQualityErrorCode.INGESTION_QUALITY_VERSION_CONFLICT);
        }
        return aggregateVersion + 1;
    }

    private void requireShape() {
        boolean receiving = status == DataBatchStatus.RECEIVING && aggregateVersion == 1
                && manifest == null && sealedQualityContractEvidence == null
                && sealedAt == null && evaluatedAt == null && publishedAt == null;
        boolean sealed = status == DataBatchStatus.SEALED && aggregateVersion == 2
                && manifest != null && sealedQualityContractEvidence != null
                && sealedAt != null && evaluatedAt == null && publishedAt == null;
        boolean assessed = (status == DataBatchStatus.QUALITY_PASSED
                        || status == DataBatchStatus.QUALITY_FAILED)
                && aggregateVersion == 3
                && manifest != null && sealedQualityContractEvidence != null
                && sealedAt != null && evaluatedAt != null && publishedAt == null;
        boolean published = status == DataBatchStatus.PUBLISHED && aggregateVersion == 4
                && manifest != null && sealedQualityContractEvidence != null
                && sealedAt != null && evaluatedAt != null && publishedAt != null;
        if (!receiving && !sealed && !assessed && !published) {
            throw IngestionQualityDomainRules.invalid();
        }
        if (sealedAt != null && sealedAt.isBefore(receivedAt)
                || evaluatedAt != null && evaluatedAt.isBefore(sealedAt)
                || publishedAt != null && publishedAt.isBefore(evaluatedAt)) {
            throw IngestionQualityDomainRules.invalid();
        }
        if (manifest != null && !receivedAt.equals(manifest.receivedAt())) {
            throw IngestionQualityDomainRules.invalid();
        }
    }

    private static IngestionQualityException invalidState() {
        return new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_INVALID_STATE);
    }

    public UUID batchId() { return batchId; }
    public BatchIdentity identity() { return identity; }
    public BatchLineage lineage() { return lineage; }
    public String declaredManifestDigest() { return declaredManifestDigest; }
    public String traceId() { return traceId; }
    public DataBatchStatus status() { return status; }
    public long aggregateVersion() { return aggregateVersion; }
    public BatchManifest manifest() { return manifest; }
    public SealedQualityContractEvidence sealedQualityContractEvidence() {
        return sealedQualityContractEvidence;
    }
    public Instant receivedAt() { return receivedAt; }
    public Instant sealedAt() { return sealedAt; }
    public Instant evaluatedAt() { return evaluatedAt; }
    public Instant publishedAt() { return publishedAt; }
}
