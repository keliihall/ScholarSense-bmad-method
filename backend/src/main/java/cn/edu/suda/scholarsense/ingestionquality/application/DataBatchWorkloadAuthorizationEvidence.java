package cn.edu.suda.scholarsense.ingestionquality.application;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Frozen workload identity and policy evidence returned by the mTLS authority. */
public record DataBatchWorkloadAuthorizationEvidence(
        String environment,
        String principalRef,
        String mtlsSanUriRef,
        String audience,
        Set<String> capabilities,
        long authorizationGeneration,
        String policyVersion,
        String policyDigest,
        Instant effectiveAt,
        Instant expiresAt,
        Instant revokedAt) {
    public static final long MAX_SAFE_AUTHORIZATION_GENERATION = 9_007_199_254_740_991L;

    public DataBatchWorkloadAuthorizationEvidence {
        environment = DataBatchCommandRules.text(environment, 128);
        principalRef = DataBatchCommandRules.text(principalRef, 256);
        mtlsSanUriRef = DataBatchCommandRules.text(mtlsSanUriRef, 512);
        URI sanUri;
        try {
            sanUri = URI.create(mtlsSanUriRef);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID", invalid);
        }
        if (!sanUri.isAbsolute() || sanUri.getScheme() == null) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
        audience = DataBatchCommandRules.text(audience, 256);
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.isEmpty() || capabilities.size() > 16) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
        LinkedHashSet<String> validated = new LinkedHashSet<>();
        for (String capability : capabilities) {
            validated.add(DataBatchCommandRules.text(capability, 128));
        }
        capabilities = Set.copyOf(validated);
        if (authorizationGeneration < 1
                || authorizationGeneration > MAX_SAFE_AUTHORIZATION_GENERATION) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
        policyVersion = DataBatchCommandRules.text(policyVersion, 128);
        policyDigest = DataBatchCommandRules.digest(policyDigest);
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!effectiveAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
    }
}
