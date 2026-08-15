package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.UUID;

/** Durable resume point and causal trace observed while selecting a claimable job. */
public record SubjectWindowRecomputeCandidate(
        UUID jobId, long checkpointSequence, String traceId, String traceparent) {
    public SubjectWindowRecomputeCandidate {
        if (jobId == null || checkpointSequence < 0 || checkpointSequence == Long.MAX_VALUE
                || traceId == null
                || !traceId.matches("^(?!0{32}$)[0-9a-f]{32}$")
                || (traceparent != null && !traceparent.matches(
                        "^00-" + traceId + "-(?!0{16})[0-9a-f]{16}-(?:00|01)$"))) {
            throw new IllegalArgumentException("INGESTION_QUALITY_RECOMPUTE_CANDIDATE_INVALID");
        }
    }

    /** Compatibility constructor for predecessor records that persisted only traceId. */
    public SubjectWindowRecomputeCandidate(
            UUID jobId, long checkpointSequence, String traceId) {
        this(jobId, checkpointSequence, traceId, null);
    }
}
