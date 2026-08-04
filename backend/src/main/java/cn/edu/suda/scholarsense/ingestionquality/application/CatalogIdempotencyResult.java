package cn.edu.suda.scholarsense.ingestionquality.application;

public record CatalogIdempotencyResult(
        String idempotencyKey,
        String requestDigest,
        CatalogView response) {}
