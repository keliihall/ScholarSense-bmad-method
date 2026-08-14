package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Optional;
import java.util.UUID;

public interface QualityEligibilitySnapshotLookupPort {
    Optional<QualityEligibilitySnapshotEvidence> findExact(
            UUID batchId, UUID snapshotId, String immutableHash);
}
