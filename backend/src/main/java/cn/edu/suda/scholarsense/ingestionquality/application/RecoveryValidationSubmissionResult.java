package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationJob;

public record RecoveryValidationSubmissionResult(
        RecoveryValidationJob job, boolean replayed) {

    public RecoveryValidationSubmissionResult {
        if (job == null) throw new IllegalArgumentException("job is required");
    }
}
