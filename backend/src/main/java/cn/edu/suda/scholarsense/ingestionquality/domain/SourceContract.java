package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.Objects;

public record SourceContract(
        String sourceId,
        String purpose,
        String schemaVersion,
        String qualityGateVersion,
        String evidenceUri,
        RuntimeEvidenceClaim runtimeEvidenceClaim,
        SourceContractMetadata metadata) {

    public SourceContract {
        require(sourceId, "^SRC-P[01]-[A-Z-]+-[0-9]{3}$", "INGESTION_QUALITY_SOURCE_ID_INVALID");
        require(purpose, "^[a-z0-9-]{3,96}$", "INGESTION_QUALITY_PURPOSE_INVALID");
        require(schemaVersion, "^[A-Z0-9-]+-[0-9]+\\.[0-9]+\\.[0-9]+$", "INGESTION_QUALITY_SCHEMA_VERSION_INVALID");
        if (!"QG-1.0.0".equals(qualityGateVersion)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_QUALITY_GATE_INVALID");
        }
        Objects.requireNonNull(runtimeEvidenceClaim, "runtimeEvidenceClaim");
        metadata = metadata == null ? SourceContractMetadata.unregistered() : metadata;
        if (runtimeEvidenceClaim == RuntimeEvidenceClaim.NONE) {
            if (evidenceUri == null || !evidenceUri.startsWith("evidence://pending/")) {
                throw new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_EVIDENCE_INVALID);
            }
        } else if (evidenceUri == null || !(evidenceUri.startsWith("evidence+sha256://")
                || evidenceUri.startsWith("sha256://") || evidenceUri.startsWith("oci://")
                || evidenceUri.startsWith("s3-version://"))) {
            throw new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_EVIDENCE_INVALID);
        }
    }

    public SourceContract(
            String sourceId, String purpose, String schemaVersion, String qualityGateVersion,
            String evidenceUri, RuntimeEvidenceClaim runtimeEvidenceClaim) {
        this(sourceId, purpose, schemaVersion, qualityGateVersion, evidenceUri,
                runtimeEvidenceClaim, SourceContractMetadata.unregistered());
    }

    private static void require(String value, String pattern, String code) {
        if (value == null || !value.matches(pattern)) {
            throw new IllegalArgumentException(code);
        }
    }
}
