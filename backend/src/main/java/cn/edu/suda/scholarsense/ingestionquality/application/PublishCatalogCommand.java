package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.UUID;

public record PublishCatalogCommand(
        UUID catalogId,
        long expectedVersion,
        UUID catalogReleaseId,
        String idempotencyKey,
        String requestDigest,
        String evidenceSetDigest,
        String actorRef,
        String traceId,
        Instant requestedAt) {}
