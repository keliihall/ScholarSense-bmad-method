package cn.edu.suda.scholarsense.ingestionquality.domain;

public enum RecoveryValidationErrorCode {
    PROVIDER_NOT_INSTALLED,
    DEPENDENCY_UNAVAILABLE,
    LEASE_EXPIRED,
    STALE_FENCE,
    RETRY_EXHAUSTED,
    CANCELLED
}
