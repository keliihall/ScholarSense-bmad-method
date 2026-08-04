package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.UUID;

public record ValidateCatalogCommand(
        UUID catalogId,
        long expectedVersion,
        String actorRef,
        String traceId,
        Instant requestedAt) {}
