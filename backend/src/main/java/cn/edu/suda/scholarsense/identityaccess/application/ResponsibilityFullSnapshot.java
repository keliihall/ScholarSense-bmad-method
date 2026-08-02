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
        String envelopeDigest,
        boolean signatureVerified,
        List<ResponsibilitySnapshotEntry> entries,
        long lineageCount,
        String canonicalLineageDigest,
        List<ResponsibilityV2LineageManifest> lineageManifests,
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
                || (!"RESPONSIBILITY-AUTHORITY-1.0.0".equals(
                                contractVersion)
                        && !"RESPONSIBILITY-AUTHORITY-2.0.0".equals(
                                contractVersion))
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
                || envelopeDigest == null
                || !envelopeDigest.matches("[0-9a-f]{64}")
                || lineageCount < 0
                || canonicalLineageDigest == null
                || !canonicalLineageDigest.matches("[0-9a-f]{64}")
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
        lineageManifests = List.copyOf(lineageManifests);
        String emptyLineageDigest =
                ResponsibilityV2LineageManifest.digest(List.of());
        if ("RESPONSIBILITY-AUTHORITY-1.0.0".equals(contractVersion)) {
            if (lineageCount != 0
                    || !lineageManifests.isEmpty()
                    || !emptyLineageDigest.equals(
                            canonicalLineageDigest)) {
                throw new IllegalArgumentException(
                        "RESPONSIBILITY_V2_LINEAGE_MANIFEST_INVALID");
            }
        } else {
            java.util.Set<String> entryTokens = entries.stream()
                    .map(ResponsibilitySnapshotEntry::relationRefToken)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            java.util.Set<String> manifestTokens = lineageManifests.stream()
                    .map(ResponsibilityV2LineageManifest::relationRefToken)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            long distinctLineages = lineageManifests.stream()
                    .map(manifest -> manifest.lineageId().value())
                    .distinct()
                    .count();
            if (lineageCount != lineageManifests.size()
                    || lineageCount != entries.size()
                    || distinctLineages != lineageCount
                    || !entryTokens.equals(manifestTokens)
                    || !ResponsibilityV2LineageManifest
                            .digest(lineageManifests)
                            .equals(canonicalLineageDigest)) {
                throw new IllegalArgumentException(
                        "RESPONSIBILITY_V2_LINEAGE_MANIFEST_INVALID");
            }
        }
    }

    /** Compatibility constructor for the immutable V1 snapshot model. */
    public ResponsibilityFullSnapshot(
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
        this(
                snapshotId,
                key,
                schemaVersion,
                contractVersion,
                businessDate,
                cutoffAt,
                sourceVersion,
                throughWatermark,
                supportingIdentityOrgWatermarks,
                sealed,
                complete,
                partitions,
                expectedCount,
                canonicalDigest,
                signatureDigest,
                canonicalDigest,
                signatureVerified,
                entries,
                0,
                ResponsibilityV2LineageManifest.digest(List.of()),
                List.of(),
                traceId);
    }
}
