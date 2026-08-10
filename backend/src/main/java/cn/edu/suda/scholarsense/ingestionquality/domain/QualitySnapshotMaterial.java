package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** The exact 26-field QSHM-1.0.0 content-hash material. */
record QualitySnapshotMaterial(
        String domainTag,
        String hashProfileVersion,
        String hashProfileDigest,
        UUID batchId,
        String sourceId,
        DataBatchStatus assessedBatchStatus,
        QualityOverallResult overallResult,
        BatchObservationWindow observationWindow,
        Instant cutoffAt,
        String watermark,
        List<QualityMetricResult> metricResults,
        List<String> impactScopeCodes,
        String sourceOwnerRef,
        String approvalRef,
        Instant effectiveAt,
        String retentionScheduleVersion,
        String qualityMetricDecisionProfileVersion,
        String qualityMetricDecisionProfileDigest,
        String qualityGateVersion,
        String qualityGateDigest,
        String canonicalizationProfile,
        String manifestDigest,
        String sourceSchemaVersion,
        String sourceSchemaDigest,
        UUID lineageId,
        UUID supersedesSnapshotId) {

    public QualitySnapshotMaterial {
        Objects.requireNonNull(domainTag);
        Objects.requireNonNull(hashProfileVersion);
        Objects.requireNonNull(hashProfileDigest);
        Objects.requireNonNull(batchId);
        Objects.requireNonNull(sourceId);
        Objects.requireNonNull(assessedBatchStatus);
        Objects.requireNonNull(overallResult);
        Objects.requireNonNull(observationWindow);
        Objects.requireNonNull(cutoffAt);
        Objects.requireNonNull(watermark);
        metricResults = List.copyOf(metricResults);
        impactScopeCodes = List.copyOf(impactScopeCodes);
        Objects.requireNonNull(sourceOwnerRef);
        Objects.requireNonNull(approvalRef);
        Objects.requireNonNull(effectiveAt);
        Objects.requireNonNull(retentionScheduleVersion);
        Objects.requireNonNull(qualityMetricDecisionProfileVersion);
        Objects.requireNonNull(qualityMetricDecisionProfileDigest);
        Objects.requireNonNull(qualityGateVersion);
        Objects.requireNonNull(qualityGateDigest);
        Objects.requireNonNull(canonicalizationProfile);
        Objects.requireNonNull(manifestDigest);
        Objects.requireNonNull(sourceSchemaVersion);
        Objects.requireNonNull(sourceSchemaDigest);
        Objects.requireNonNull(lineageId);
    }
}
