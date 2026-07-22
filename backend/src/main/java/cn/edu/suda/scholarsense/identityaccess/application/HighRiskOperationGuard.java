package cn.edu.suda.scholarsense.identityaccess.application;

/** Shared fail-closed conformance point for identity operations that expose or mutate sensitive state. */
@FunctionalInterface
public interface HighRiskOperationGuard {
    void requireAvailable(String traceId);
}
