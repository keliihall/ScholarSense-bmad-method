package cn.edu.suda.scholarsense.ingestionquality.application;

public enum QualityTaskRelayResult {
    IDLE,
    CONFIRMED,
    RETRY_SCHEDULED,
    FAILED,
    FENCED
}
