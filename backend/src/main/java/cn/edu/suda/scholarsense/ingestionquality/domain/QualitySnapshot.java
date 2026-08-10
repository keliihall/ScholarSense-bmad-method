package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable owner-domain quality evidence.
 *
 * <p>The first 26 components are QSHM hash material. The final five runtime components remain
 * immutable snapshot evidence but are deliberately excluded from the content hash.
 */
public record QualitySnapshot(
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
        UUID supersedesSnapshotId,
        UUID snapshotId,
        Instant evaluatedAt,
        String traceId,
        long aggregateVersion,
        String immutableHash) {

    private static final String DOMAIN_TAG =
            "scholarsense.ingestion-quality.quality-snapshot.immutable-hash.v1";
    private static final String HASH_PROFILE_VERSION = "QSHM-1.0.0";
    private static final String APPROVAL_REF = "AUTH-2026-08-08-001";
    private static final String RETENTION_VERSION = "RS-1.0.0";
    private static final String QMDP_VERSION = "QMDP-1.0.0";
    private static final String QUALITY_GATE_VERSION = "QG-1.0.0";
    private static final String CANONICALIZATION_PROFILE =
            "SCHOLARSENSE-CANONICAL-JSON-1.0.0";
    private static final Comparator<String> UNICODE_CODE_POINT_ORDER =
            QualitySnapshot::compareCodePoints;

    public QualitySnapshot {
        requireExact(domainTag, DOMAIN_TAG);
        requireExact(hashProfileVersion, HASH_PROFILE_VERSION);
        hashProfileDigest = IngestionQualityDomainRules.requireSha256(hashProfileDigest);
        batchId = IngestionQualityDomainRules.requireUuidV7(batchId);
        if (sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z-]+-[0-9]{3}$")) {
            throw invalid();
        }
        Objects.requireNonNull(assessedBatchStatus);
        Objects.requireNonNull(overallResult);
        boolean passed = assessedBatchStatus == DataBatchStatus.QUALITY_PASSED
                && overallResult == QualityOverallResult.QUALITY_PASSED;
        boolean failed = assessedBatchStatus == DataBatchStatus.QUALITY_FAILED
                && overallResult == QualityOverallResult.QUALITY_FAILED;
        if (!passed && !failed) throw invalid();

        observationWindow = Objects.requireNonNull(observationWindow);
        requireMicrosecond(observationWindow.startAt());
        requireMicrosecond(observationWindow.endAt());
        cutoffAt = requireMicrosecond(cutoffAt);
        watermark = IngestionQualityDomainRules.requireText(watermark, 512);
        QualityAssessment assessment = new QualityAssessment(overallResult, metricResults);
        metricResults = assessment.metricResults();
        impactScopeCodes = normalizedImpactScopes(impactScopeCodes);
        sourceOwnerRef = IngestionQualityDomainRules.requireText(sourceOwnerRef, 256);
        requireExact(approvalRef, APPROVAL_REF);
        effectiveAt = requireMicrosecond(effectiveAt);
        requireExact(retentionScheduleVersion, RETENTION_VERSION);
        requireExact(qualityMetricDecisionProfileVersion, QMDP_VERSION);
        qualityMetricDecisionProfileDigest = IngestionQualityDomainRules.requireSha256(
                qualityMetricDecisionProfileDigest);
        requireExact(qualityGateVersion, QUALITY_GATE_VERSION);
        qualityGateDigest = IngestionQualityDomainRules.requireSha256(qualityGateDigest);
        requireExact(canonicalizationProfile, CANONICALIZATION_PROFILE);
        manifestDigest = IngestionQualityDomainRules.requireSha256(manifestDigest);
        sourceSchemaVersion = IngestionQualityDomainRules.requireText(sourceSchemaVersion, 128);
        sourceSchemaDigest = IngestionQualityDomainRules.requireSha256(sourceSchemaDigest);
        lineageId = IngestionQualityDomainRules.requireUuidV7(lineageId);
        if (supersedesSnapshotId != null) {
            supersedesSnapshotId = IngestionQualityDomainRules.requireUuidV7(
                    supersedesSnapshotId);
        }
        snapshotId = IngestionQualityDomainRules.requireUuidV7(snapshotId);
        if (snapshotId.equals(supersedesSnapshotId)) throw invalid();
        evaluatedAt = requireMicrosecond(evaluatedAt);
        if (effectiveAt.isAfter(evaluatedAt)) throw invalid();
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")
                || traceId.matches("0{32}")) {
            throw invalid();
        }
        aggregateVersion = IngestionQualityDomainRules.requireVersion(aggregateVersion);
        immutableHash = IngestionQualityDomainRules.requireSha256(immutableHash);
        if (immutableHash.equals("sha256:" + "0".repeat(64))) throw invalid();
    }

    private static List<String> normalizedImpactScopes(List<String> values) {
        if (values == null) throw invalid();
        ArrayList<String> result = new ArrayList<>(values.size());
        for (String value : values) {
            result.add(IngestionQualityDomainRules.requireText(value, 64));
        }
        if (new HashSet<>(result).size() != result.size()) throw invalid();
        result.sort(UNICODE_CODE_POINT_ORDER);
        return List.copyOf(result);
    }

    private static Instant requireMicrosecond(Instant value) {
        if (value == null || value.getNano() % 1_000 != 0) throw invalid();
        return value;
    }

    private static void requireExact(String actual, String expected) {
        if (!expected.equals(actual)) throw invalid();
    }

    private static int compareCodePoints(String left, String right) {
        int leftOffset = 0;
        int rightOffset = 0;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            int leftPoint = left.codePointAt(leftOffset);
            int rightPoint = right.codePointAt(rightOffset);
            if (leftPoint != rightPoint) return Integer.compare(leftPoint, rightPoint);
            leftOffset += Character.charCount(leftPoint);
            rightOffset += Character.charCount(rightPoint);
        }
        return Integer.compare(left.length() - leftOffset, right.length() - rightOffset);
    }

    private static IngestionQualityException invalid() {
        return IngestionQualityDomainRules.invalid();
    }
}
