package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;
import java.util.UUID;

public record PendingQualityPair(
        UUID assessedEventId,
        UUID batchId,
        UUID snapshotId,
        String snapshotImmutableHash,
        long sourceVersion,
        UUID lineageId) {
    public PendingQualityPair {
        assessedEventId = Objects.requireNonNull(assessedEventId);
        batchId = Objects.requireNonNull(batchId);
        snapshotId = Objects.requireNonNull(snapshotId);
        snapshotImmutableHash = Objects.requireNonNull(snapshotImmutableHash);
        if (sourceVersion < 1) throw new IllegalArgumentException("INGESTION_QUALITY_PAIR_INVALID");
        lineageId = Objects.requireNonNull(lineageId);
    }

    public boolean matches(UpstreamQualityEvent event) {
        return batchId.equals(event.batchId())
                && snapshotId.equals(event.snapshotId())
                && snapshotImmutableHash.equals(event.snapshotImmutableHash())
                && sourceVersion == event.sourceVersion()
                && lineageId.equals(event.lineageId());
    }
}
