package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.UUID;

public record RecoveryValidationCandidate(UUID jobId, long checkpointVersion) {
    public RecoveryValidationCandidate {
        if (jobId == null || jobId.version() != 7 || jobId.variant() != 2
                || checkpointVersion < 0 || checkpointVersion > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("INGESTION_QUALITY_VALIDATION_CANDIDATE_INVALID");
        }
    }
}
