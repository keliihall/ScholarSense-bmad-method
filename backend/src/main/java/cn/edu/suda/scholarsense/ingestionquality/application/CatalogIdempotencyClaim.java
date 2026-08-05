package cn.edu.suda.scholarsense.ingestionquality.application;

public record CatalogIdempotencyClaim(Status status, CatalogIdempotencyResult result) {
    public enum Status { ACQUIRED, REPLAY, MISMATCH }

    public CatalogIdempotencyClaim {
        if ((status == Status.REPLAY) != (result != null)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_IDEMPOTENCY_CLAIM_INVALID");
        }
    }

    public static CatalogIdempotencyClaim acquired() {
        return new CatalogIdempotencyClaim(Status.ACQUIRED, null);
    }

    public static CatalogIdempotencyClaim replay(CatalogIdempotencyResult result) {
        return new CatalogIdempotencyClaim(Status.REPLAY, result);
    }

    public static CatalogIdempotencyClaim mismatch() {
        return new CatalogIdempotencyClaim(Status.MISMATCH, null);
    }
}
