package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.UUID;

/** Durable resume point observed while selecting a claimable job. */
public record SubjectWindowRecomputeCandidate(UUID jobId, long checkpointSequence) {
    public SubjectWindowRecomputeCandidate {
        if (jobId == null || checkpointSequence < 0 || checkpointSequence == Long.MAX_VALUE) {
            throw new IllegalArgumentException("INGESTION_QUALITY_RECOMPUTE_CANDIDATE_INVALID");
        }
    }
}
