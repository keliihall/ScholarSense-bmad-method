package cn.edu.suda.scholarsense.signalevaluation.api;

/** Privacy-safe provider failures; no raw row or free-text detail crosses the public API. */
public enum RecoverySampleProviderError {
    PROVIDER_UNAVAILABLE,
    PROVIDER_NOT_INSTALLED,
    UNKNOWN_VERSION_OR_DIGEST,
    REQUEST_CONFLICT,
    BOUNDS_EXCEEDED,
    TIMEOUT
}
