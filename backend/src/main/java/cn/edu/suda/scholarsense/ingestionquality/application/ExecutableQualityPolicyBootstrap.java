package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;

/** Startup gate for the executable quality contract. */
public final class ExecutableQualityPolicyBootstrap {
    private final ExecutableQualityPolicyPort policies;

    public ExecutableQualityPolicyBootstrap(ExecutableQualityPolicyPort policies) {
        this.policies = Objects.requireNonNull(policies);
    }

    public VerifiedQualityContract start() {
        VerifiedQualityContract verified = policies.loadVerified();
        if (verified == null) throw contractInvalid();
        return verified;
    }

    private static IngestionQualityApplicationException contractInvalid() {
        return new IngestionQualityApplicationException("INGESTION_QUALITY_CONTRACT_INVALID");
    }
}
