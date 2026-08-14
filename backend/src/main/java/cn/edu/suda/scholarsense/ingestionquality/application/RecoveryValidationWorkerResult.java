package cn.edu.suda.scholarsense.ingestionquality.application;

public record RecoveryValidationWorkerResult(
        int claimed,
        int succeeded,
        int failed,
        int cancelled,
        int fenced,
        int checkpoints,
        int yielded,
        int retriesScheduled) {

    public static final RecoveryValidationWorkerResult IDLE =
            new RecoveryValidationWorkerResult(0, 0, 0, 0, 0, 0, 0, 0);
}
