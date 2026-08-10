package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;

/** Captures an evaluation contract and reloads it immediately before the final commit. */
public final class ExecutableQualityPolicyGuard {
    private final ExecutableQualityPolicyPort policies;

    public ExecutableQualityPolicyGuard(ExecutableQualityPolicyPort policies) {
        this.policies = Objects.requireNonNull(policies);
    }

    public VerifiedQualityContract capture() {
        return load();
    }

    public VerifiedQualityContract revalidate(QualityContractAttestation captured) {
        if (captured == null) throw contractInvalid();
        VerifiedQualityContract current = load();
        if (!captured.equals(current.attestation())) throw contractInvalid();
        return current;
    }

    private VerifiedQualityContract load() {
        VerifiedQualityContract verified = policies.loadVerified();
        if (verified == null) throw contractInvalid();
        return verified;
    }

    private static IngestionQualityApplicationException contractInvalid() {
        return new IngestionQualityApplicationException("INGESTION_QUALITY_CONTRACT_INVALID");
    }
}
