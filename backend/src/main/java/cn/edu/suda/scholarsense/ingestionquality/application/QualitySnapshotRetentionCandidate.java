package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;
import java.util.UUID;

/** Authoritative owner-side due snapshot material exposed without raw-table privileges. */
public record QualitySnapshotRetentionCandidate(
        UUID executionId,
        QualitySnapshotRetentionScopeCanonicalizer.Material scope,
        String scopeDigest) {
    public QualitySnapshotRetentionCandidate {
        executionId = Objects.requireNonNull(executionId);
        scope = Objects.requireNonNull(scope);
        if (executionId.version() != 7
                || executionId.variant() != 2
                || !QualitySnapshotRetentionScopeCanonicalizer.OBJECT_TYPE.equals(
                        scope.objectType())
                || scope.snapshotId().version() != 7
                || scope.snapshotId().variant() != 2
                || !QualitySnapshotRetentionScopeCanonicalizer.POLICY_VERSION.equals(
                        scope.retentionPolicyVersion())
                || !QualitySnapshotRetentionScopeCanonicalizer.SCHEDULE_VERSION.equals(
                        scope.retentionScheduleVersion())
                || !QualitySnapshotRetentionScopeCanonicalizer.retentionDueAt(scope.evaluatedAt())
                        .equals(scope.retentionDueAt())
                || scopeDigest == null
                || !scopeDigest.matches("sha256:[0-9a-f]{64}")
                || !scopeDigest.equals(QualitySnapshotRetentionScopeCanonicalizer.digest(scope))) {
            throw new IllegalArgumentException(
                    "INGESTION_QUALITY_RETENTION_CANDIDATE_INVALID");
        }
    }
}
