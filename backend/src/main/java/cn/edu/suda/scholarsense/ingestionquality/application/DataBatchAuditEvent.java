package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import java.time.Instant;
import java.util.UUID;

public record DataBatchAuditEvent(
        String action,
        String result,
        UUID batchId,
        long aggregateVersion,
        String actorRef,
        String traceId,
        Instant occurredAt,
        TimeSourceProfile timeSourceProfile,
        String requestDigest) {
}
