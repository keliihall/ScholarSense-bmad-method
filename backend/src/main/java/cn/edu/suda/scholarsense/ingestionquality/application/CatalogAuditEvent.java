package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.UUID;

public record CatalogAuditEvent(
        String action,
        String result,
        UUID catalogId,
        Long aggregateVersion,
        String actorRef,
        String traceId,
        Instant occurredAt) {}
