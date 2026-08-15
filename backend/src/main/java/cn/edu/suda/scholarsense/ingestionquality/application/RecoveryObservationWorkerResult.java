package cn.edu.suda.scholarsense.ingestionquality.application;

public record RecoveryObservationWorkerResult(
        int claimed,
        int completed,
        int retriesScheduled,
        int yielded,
        int failed,
        int fenced) {

    public RecoveryObservationWorkerResult {
        if (claimed < 0 || completed < 0 || retriesScheduled < 0
                || yielded < 0 || failed < 0 || fenced < 0) {
            throw new IllegalArgumentException("RECOVERY_OBSERVATION_WORKER_RESULT_INVALID");
        }
    }
}
