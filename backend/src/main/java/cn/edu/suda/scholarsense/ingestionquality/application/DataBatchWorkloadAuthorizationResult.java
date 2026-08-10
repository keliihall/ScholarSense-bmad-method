package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;

/** Typed fail-closed outcome from the workload authorization authority. */
public record DataBatchWorkloadAuthorizationResult(
        Status status,
        DataBatchWorkloadAuthorizationEvidence evidence,
        long currentAuthorizationGeneration) {
    public enum Status {
        ALLOW,
        DENY,
        DEPENDENCY_UNAVAILABLE
    }

    public DataBatchWorkloadAuthorizationResult {
        Objects.requireNonNull(status, "status");
        if (currentAuthorizationGeneration < -1
                || currentAuthorizationGeneration
                > DataBatchWorkloadAuthorizationEvidence.MAX_SAFE_AUTHORIZATION_GENERATION) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
        if (status == Status.ALLOW) {
            Objects.requireNonNull(evidence, "evidence");
            if (currentAuthorizationGeneration < 1) {
                throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
            }
        } else if (evidence != null
                || (status == Status.DENY && currentAuthorizationGeneration < 1)
                || (status == Status.DEPENDENCY_UNAVAILABLE
                && currentAuthorizationGeneration != -1)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
    }

    public static DataBatchWorkloadAuthorizationResult allow(
            DataBatchWorkloadAuthorizationEvidence evidence,
            long currentAuthorizationGeneration) {
        return new DataBatchWorkloadAuthorizationResult(
                Status.ALLOW, evidence, currentAuthorizationGeneration);
    }

    public static DataBatchWorkloadAuthorizationResult deny(
            long currentAuthorizationGeneration) {
        return new DataBatchWorkloadAuthorizationResult(
                Status.DENY, null, currentAuthorizationGeneration);
    }

    public static DataBatchWorkloadAuthorizationResult dependencyUnavailable() {
        return new DataBatchWorkloadAuthorizationResult(
                Status.DEPENDENCY_UNAVAILABLE, null, -1);
    }
}
