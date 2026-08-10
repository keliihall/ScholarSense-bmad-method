package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The 42-leaf QualitySnapshot projection frozen by field-projection 1.1.0. */
public record QualitySnapshotView(
        UUID snapshotId,
        UUID batchId,
        String sourceId,
        String assessedBatchStatus,
        String overallResult,
        ObservationWindowView observationWindow,
        Instant cutoffAt,
        Instant evaluatedAt,
        String watermark,
        List<QualitySnapshotMetricView> metricResults,
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
        String immutableHash,
        String traceId,
        UUID lineageId,
        UUID supersedesSnapshotId,
        long aggregateVersion) {
    public QualitySnapshotView {
        metricResults = List.copyOf(metricResults);
        impactScopeCodes = List.copyOf(impactScopeCodes);
    }

    static QualitySnapshotView from(QualitySnapshot snapshot) {
        return new QualitySnapshotView(
                snapshot.snapshotId(), snapshot.batchId(), snapshot.sourceId(),
                snapshot.assessedBatchStatus().wireValue(),
                snapshot.overallResult().wireValue(),
                new ObservationWindowView(
                        snapshot.observationWindow().startAt(), snapshot.observationWindow().endAt()),
                snapshot.cutoffAt(), snapshot.evaluatedAt(), snapshot.watermark(),
                snapshot.metricResults().stream().map(QualitySnapshotMetricView::from).toList(),
                snapshot.impactScopeCodes(), snapshot.sourceOwnerRef(), snapshot.approvalRef(),
                snapshot.effectiveAt(), snapshot.retentionScheduleVersion(),
                snapshot.qualityMetricDecisionProfileVersion(),
                snapshot.qualityMetricDecisionProfileDigest(), snapshot.qualityGateVersion(),
                snapshot.qualityGateDigest(), snapshot.canonicalizationProfile(),
                snapshot.manifestDigest(), snapshot.sourceSchemaVersion(),
                snapshot.sourceSchemaDigest(), snapshot.immutableHash(), snapshot.traceId(),
                snapshot.lineageId(), snapshot.supersedesSnapshotId(), snapshot.aggregateVersion());
    }

    public record ObservationWindowView(Instant startAt, Instant endAt) {}
}
