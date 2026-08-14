package cn.edu.suda.scholarsense.ingestionquality.application;

public enum RecoveryValidationDependencyError {
    DEPENDENCY_UNAVAILABLE,
    PROVIDER_NOT_INSTALLED,
    UNKNOWN_VERSION_OR_DIGEST,
    REQUEST_CONFLICT,
    BOUNDS_EXCEEDED,
    TIMEOUT
}
