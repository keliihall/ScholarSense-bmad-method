package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ResponsibilityFullSnapshot(
        UUID snapshotId,
        CheckpointKey key,
        String schemaVersion,
        String contractVersion,
        LocalDate businessDate,
        Instant cutoffAt,
        long sourceVersion,
        long throughWatermark,
        Map<String, Long> supportingIdentityOrgWatermarks,
        boolean sealed,
        boolean complete,
        List<String> partitions,
        long expectedCount,
        String canonicalDigest,
        String signatureDigest,
        boolean signatureVerified,
        List<ResponsibilitySnapshotEntry> entries,
        String traceId) {
    public ResponsibilityFullSnapshot {
        if (snapshotId == null
                || snapshotId.version() != 7
                || snapshotId.variant() != 2
                || key == null
                || !"responsibility".equals(
                        key.consumerProjection())
                || !"RESPONSIBILITY-SNAPSHOT-1.0.0".equals(
                        schemaVersion)
                || !"RESPONSIBILITY-AUTHORITY-1.0.0".equals(
                        contractVersion)
                || businessDate == null
                || cutoffAt == null
                || sourceVersion < 1
                || throughWatermark < 0
                || !sealed
                || !complete
                || expectedCount < 0
                || expectedCount > 1_000_000
                || canonicalDigest == null
                || !canonicalDigest.matches("[0-9a-f]{64}")
                || signatureDigest == null
                || !signatureDigest.matches("[0-9a-f]{64}")
                || traceId == null
                || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SNAPSHOT_INVALID");
        }
        supportingIdentityOrgWatermarks =
                Map.copyOf(supportingIdentityOrgWatermarks);
        if (supportingIdentityOrgWatermarks.isEmpty()) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_DEPENDENCY_VECTOR_INVALID");
        }
        partitions = List.copyOf(partitions);
        if (partitions.isEmpty()
                || partitions.stream().distinct().count()
                        != partitions.size()
                || !partitions.contains(key.partitionId())) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SNAPSHOT_PARTITION_MISSING");
        }
        entries = List.copyOf(entries);
        if (expectedCount != entries.size()) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SNAPSHOT_COUNT_MISMATCH");
        }
    }
}
