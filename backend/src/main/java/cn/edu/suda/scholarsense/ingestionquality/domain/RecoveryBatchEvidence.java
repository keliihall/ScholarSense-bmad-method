package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One immutable Story 2.3/2.4 assessed-passed to published evidence pair.
 * Transport identifiers and timestamps are intentionally absent from ordering.
 */
public record RecoveryBatchEvidence(
        String sourceId,
        long sourceVersionOrdinal,
        long lineageRevision,
        UUID assessedBatchId,
        UUID assessedSnapshotId,
        String assessedSnapshotImmutableHash,
        AssessmentStatus assessmentStatus,
        long assessedAggregateVersion,
        UUID publishedBatchId,
        UUID publishedSnapshotId,
        String publishedSnapshotImmutableHash,
        PublicationStatus publicationStatus,
        long publishedAggregateVersion) {

    public RecoveryBatchEvidence {
        if (sourceId == null
                || !sourceId.matches("^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$")
                || sourceVersionOrdinal < 1
                || sourceVersionOrdinal > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || lineageRevision < 0
                || lineageRevision > IngestionQualityDomainRules.MAX_SAFE_VERSION) {
            throw invalid();
        }
        assessedBatchId = IngestionQualityDomainRules.requireUuidV7(assessedBatchId);
        assessedSnapshotId = IngestionQualityDomainRules.requireUuidV7(assessedSnapshotId);
        assessedSnapshotImmutableHash = digest(assessedSnapshotImmutableHash);
        if (assessmentStatus == null) throw invalid();
        publishedBatchId = IngestionQualityDomainRules.requireUuidV7(publishedBatchId);
        publishedSnapshotId = IngestionQualityDomainRules.requireUuidV7(publishedSnapshotId);
        publishedSnapshotImmutableHash = digest(publishedSnapshotImmutableHash);
        if (publicationStatus == null) throw invalid();
        if (assessedAggregateVersion < 1
                || assessedAggregateVersion > IngestionQualityDomainRules.MAX_SAFE_VERSION
                || publishedAggregateVersion < 1
                || publishedAggregateVersion > IngestionQualityDomainRules.MAX_SAFE_VERSION) {
            throw invalid();
        }
    }

    public boolean exactAssessedPassedToPublishedPair() {
        return assessmentStatus == AssessmentStatus.PASSED
                && publicationStatus == PublicationStatus.PUBLISHED
                && assessedAggregateVersion < IngestionQualityDomainRules.MAX_SAFE_VERSION
                && publishedAggregateVersion == assessedAggregateVersion + 1
                && assessedBatchId.equals(publishedBatchId)
                && assessedSnapshotId.equals(publishedSnapshotId)
                && assessedSnapshotImmutableHash.equals(publishedSnapshotImmutableHash);
    }

    /**
     * Counts the consecutive valid run at the newest business-sequence tail.
     * Each ordinal may have a zero-based direct correction chain; only its highest
     * lineage revision is the canonical evidence for that business batch.
     */
    public static int consecutivePassedPublishedCount(List<RecoveryBatchEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) return 0;
        List<RecoveryBatchEvidence> ordered = new ArrayList<>(evidence);
        if (ordered.stream().anyMatch(value -> value == null)) return 0;

        Set<String> sources = new HashSet<>();
        ordered.forEach(value -> sources.add(value.sourceId));
        if (sources.size() != 1) return 0;

        Set<BusinessSequence> seen = new HashSet<>();
        for (RecoveryBatchEvidence value : ordered) {
            if (!seen.add(new BusinessSequence(
                    value.sourceVersionOrdinal, value.lineageRevision))) return 0;
        }

        Map<Long, List<RecoveryBatchEvidence>> byOrdinal = new TreeMap<>();
        for (RecoveryBatchEvidence value : ordered) {
            byOrdinal.computeIfAbsent(value.sourceVersionOrdinal, ignored -> new ArrayList<>())
                    .add(value);
        }
        List<RecoveryBatchEvidence> canonical = new ArrayList<>();
        for (List<RecoveryBatchEvidence> revisions : byOrdinal.values()) {
            revisions.sort(Comparator.comparingLong(RecoveryBatchEvidence::lineageRevision));
            for (int revision = 0; revision < revisions.size(); revision++) {
                if (revisions.get(revision).lineageRevision != revision) return 0;
            }
            canonical.add(revisions.getLast());
        }

        int tail = 0;
        for (int index = canonical.size() - 1; index >= 0; index--) {
            RecoveryBatchEvidence current = canonical.get(index);
            if (!current.exactAssessedPassedToPublishedPair()) break;
            tail++;
            if (index == 0) break;
            long previous = canonical.get(index - 1).sourceVersionOrdinal;
            long next = current.sourceVersionOrdinal;
            if (previous == IngestionQualityDomainRules.MAX_SAFE_VERSION
                    || next != previous + 1) break;
        }
        return tail;
    }

    public enum AssessmentStatus { PASSED, FAILED, UNKNOWN }

    public enum PublicationStatus { PUBLISHED, UNPUBLISHED, UNKNOWN }

    private record BusinessSequence(long sourceVersionOrdinal, long lineageRevision) {}

    private static String digest(String value) {
        if (value == null || !value.matches("^sha256:[0-9a-f]{64}$")) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("QUALITY_RECOVERY_BATCH_EVIDENCE_INVALID");
    }
}
