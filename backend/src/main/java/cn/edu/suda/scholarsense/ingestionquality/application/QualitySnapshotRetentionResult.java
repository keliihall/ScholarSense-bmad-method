package cn.edu.suda.scholarsense.ingestionquality.application;

/** Outcomes currently materialized by the owner-local PostgreSQL retention boundary. */
public enum QualitySnapshotRetentionResult {
    BLOCKED,
    COMPLETED;

    public static QualitySnapshotRetentionResult fromDatabase(String value) {
        return switch (value) {
            case "blocked" -> BLOCKED;
            case "completed" -> COMPLETED;
            default -> throw new IngestionQualityApplicationException(
                    "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
        };
    }
}
