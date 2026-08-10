package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Optional;

/** Finds one currently due snapshot; this is not a durable lease or fencing claim. */
@FunctionalInterface
public interface QualitySnapshotRetentionCandidatePort {
    Optional<QualitySnapshotRetentionCandidate> findNextDue();
}
