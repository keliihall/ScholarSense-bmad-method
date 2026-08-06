package cn.edu.suda.scholarsense.ingestionquality.domain;

public record CatalogEvidenceScenario(String id, String result, String observationDigest) {
    public CatalogEvidenceScenario {
        if (id == null || !id.matches("^[a-z0-9][a-z0-9-]{2,95}$")) {
            throw new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_EVIDENCE_INVALID);
        }
        if (!"pass".equals(result)) {
            throw new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_EVIDENCE_INVALID);
        }
        requireDigest(observationDigest);
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("^sha256:[0-9a-f]{64}$")) {
            throw new IngestionQualityException(IngestionQualityErrorCode.INGESTION_QUALITY_EVIDENCE_INVALID);
        }
    }
}
