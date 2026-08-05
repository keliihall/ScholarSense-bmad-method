package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CatalogIdempotencyPort {
    Optional<CatalogIdempotencyResult> find(String idempotencyKey, Instant now);
    CatalogIdempotencyClaim claim(
            String idempotencyKey, String requestDigest, UUID catalogId, Instant now);
    void complete(CatalogIdempotencyResult result, Instant completedAt);
}
