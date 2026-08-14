package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationCheckpoint;
import java.util.UUID;

/** Opaque resume request sent outside the owner transaction. */
public record RecoveryValidationExecutionRequest(
        UUID jobId,
        long leaseGeneration,
        long checkpointVersion,
        RecoveryValidationCheckpoint checkpoint) {

    public RecoveryValidationExecutionRequest {
        if (jobId == null || jobId.version() != 7 || jobId.variant() != 2
                || leaseGeneration < 1 || checkpointVersion < 0
                || (checkpoint == null) != (checkpointVersion == 0)
                || checkpoint != null && checkpoint.checkpointVersion() != checkpointVersion) {
            throw new IllegalArgumentException("INGESTION_QUALITY_VALIDATION_EXECUTION_INVALID");
        }
    }
}
