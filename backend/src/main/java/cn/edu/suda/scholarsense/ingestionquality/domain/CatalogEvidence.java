package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Privacy-bounded, immutable evidence for one frozen source contract. */
public record CatalogEvidence(
        String sourceId,
        String evidenceUri,
        String contractVersion,
        String schemaVersion,
        String qualityGateVersion,
        String environment,
        String authority,
        String candidateCommit,
        String candidateTree,
        long handoffRevision,
        String handoffDigest,
        String inputDigest,
        List<CatalogEvidenceScenario> scenarios,
        String result,
        Instant occurredAt,
        String signatureDigest,
        String evidenceDigest,
        String cleanupResult,
        RuntimeEvidenceClaim runtimeEvidenceClaim) {

    public CatalogEvidence {
        if (sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z-]+-[0-9]{3}$")
                || !"DCC-1.0.0".equals(contractVersion)
                || schemaVersion == null
                || !schemaVersion.matches("^[A-Z0-9-]+-[0-9]+\\.[0-9]+\\.[0-9]+$")
                || !"QG-1.0.0".equals(qualityGateVersion)
                || !("test".equals(environment) || "stage".equals(environment) || "prod".equals(environment))
                || authority == null || authority.isBlank()
                || candidateCommit == null || !candidateCommit.matches("^[0-9a-f]{40}$")
                || candidateTree == null || !candidateTree.matches("^[0-9a-f]{40}$")
                || handoffRevision < 1
                || handoffRevision > 9_007_199_254_740_991L
                || !"pass".equals(result) || !"pass".equals(cleanupResult)
                || runtimeEvidenceClaim != RuntimeEvidenceClaim.TARGET_VERIFIED
                || !immutableEvidenceUri(evidenceUri)) {
            throw invalid();
        }
        requireDigest(handoffDigest);
        requireDigest(inputDigest);
        requireDigest(signatureDigest);
        requireDigest(evidenceDigest);
        scenarios = List.copyOf(Objects.requireNonNull(scenarios));
        if (scenarios.isEmpty()) throw invalid();
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static boolean immutableEvidenceUri(String value) {
        return value != null && (value.startsWith("evidence+sha256://")
                || value.startsWith("sha256://") || value.startsWith("oci://")
                || value.startsWith("s3-version://"));
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("^sha256:[0-9a-f]{64}$")) throw invalid();
    }

    private static IngestionQualityException invalid() {
        return new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_EVIDENCE_INVALID);
    }
}
