package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

/** Marks transport input failures; domain/repository invariants must not be classified as HTTP 400. */
final class CatalogRequestException extends RuntimeException {
    CatalogRequestException() {
        super("INGESTION_QUALITY_REQUEST_INVALID");
    }

    CatalogRequestException(Throwable cause) {
        super("INGESTION_QUALITY_REQUEST_INVALID", cause);
    }
}
