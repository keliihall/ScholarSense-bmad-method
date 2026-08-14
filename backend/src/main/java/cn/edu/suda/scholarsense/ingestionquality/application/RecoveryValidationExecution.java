package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationCheckpoint;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationErrorCode;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationResult;

public record RecoveryValidationExecution(
        Outcome outcome,
        RecoveryValidationCheckpoint checkpoint,
        RecoveryValidationResult result,
        RecoveryValidationErrorCode errorCode) {

    public enum Outcome { CHECKPOINT, SUCCEEDED, FAILED, CANCELLED }

    public RecoveryValidationExecution {
        if (outcome == null
                || (outcome == Outcome.CHECKPOINT) != (checkpoint != null)
                || (outcome == Outcome.SUCCEEDED) != (result != null)
                || (outcome == Outcome.FAILED) != (errorCode != null)
                || (errorCode != null && (errorCode == RecoveryValidationErrorCode.CANCELLED
                    || errorCode == RecoveryValidationErrorCode.STALE_FENCE
                    || errorCode == RecoveryValidationErrorCode.LEASE_EXPIRED))) {
            throw new IllegalArgumentException("INGESTION_QUALITY_VALIDATION_EXECUTION_INVALID");
        }
    }

    public static RecoveryValidationExecution checkpoint(RecoveryValidationCheckpoint value) {
        return new RecoveryValidationExecution(Outcome.CHECKPOINT, value, null, null);
    }

    public static RecoveryValidationExecution succeeded(RecoveryValidationResult value) {
        return new RecoveryValidationExecution(Outcome.SUCCEEDED, null, value, null);
    }

    public static RecoveryValidationExecution failed(RecoveryValidationErrorCode code) {
        return new RecoveryValidationExecution(Outcome.FAILED, null, null, code);
    }

    public static RecoveryValidationExecution cancelled() {
        return new RecoveryValidationExecution(Outcome.CANCELLED, null, null, null);
    }
}
