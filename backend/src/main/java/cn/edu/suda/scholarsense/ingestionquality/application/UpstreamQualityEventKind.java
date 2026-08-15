package cn.edu.suda.scholarsense.ingestionquality.application;

public enum UpstreamQualityEventKind {
    ASSESSED_FAILED(
            "scholarsense.ingestion-quality.data-batch.quality-assessed.v1",
            "DATA-BATCH-QUALITY-ASSESSED-1.0.0", 3),
    ASSESSED_PASSED(
            "scholarsense.ingestion-quality.data-batch.quality-assessed.v1",
            "DATA-BATCH-QUALITY-ASSESSED-1.0.0", 3),
    PUBLISHED(
            "scholarsense.ingestion-quality.data-batch.published.v1",
            "DATA-BATCH-PUBLISHED-1.0.0", 4);

    private final String eventType;
    private final String schemaVersion;
    private final long batchAggregateVersion;

    UpstreamQualityEventKind(
            String eventType, String schemaVersion, long batchAggregateVersion) {
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.batchAggregateVersion = batchAggregateVersion;
    }

    public String eventType() {
        return eventType;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public long batchAggregateVersion() {
        return batchAggregateVersion;
    }

    public boolean matches(String candidateType, String candidateSchema) {
        return (eventType.equals(candidateType) && schemaVersion.equals(candidateSchema))
                || (eventType.replace(".v1", ".v2").equals(candidateType)
                    && schemaVersion.replace("-1.0.0", "-2.0.0").equals(candidateSchema));
    }
}
