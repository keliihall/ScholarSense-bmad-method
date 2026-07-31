package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record IdentitySyncAuditEvent(
        String action,
        String outcome,
        String reasonCode,
        UUID jobId,
        int attemptNo,
        long fencingToken,
        long sourceVersion,
        long sourceWatermark,
        long aggregateVersion,
        String traceId,
        Instant occurredAt,
        Map<String, String> policyVersions,
        UUID auditedObjectId) {
    public IdentitySyncAuditEvent {
        policyVersions = Map.copyOf(policyVersions);
        if (auditedObjectId == null) {
            auditedObjectId = jobId;
        }
    }

    public IdentitySyncAuditEvent(
            String action,
            String outcome,
            String reasonCode,
            UUID jobId,
            int attemptNo,
            long fencingToken,
            long sourceVersion,
            long sourceWatermark,
            long aggregateVersion,
            String traceId,
            Instant occurredAt,
            Map<String, String> policyVersions) {
        this(
                action,
                outcome,
                reasonCode,
                jobId,
                attemptNo,
                fencingToken,
                sourceVersion,
                sourceWatermark,
                aggregateVersion,
                traceId,
                occurredAt,
                policyVersions,
                jobId);
    }
}
