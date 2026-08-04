package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Optional;

public interface CatalogIdempotencyPort {
    Optional<CatalogIdempotencyResult> find(String idempotencyKey);
    void save(CatalogIdempotencyResult result);
}
