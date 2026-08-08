package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SubjectRegistryAuditEvent(
        String action,
        String resultCode,
        UUID objectId,
        long aggregateVersion,
        String auditActorRef,
        String sourceIp,
        String traceId,
        Instant occurredAt,
        TimeSourceProfile timeSourceProfile,
        String idempotencyKeyDigest) {
    public SubjectRegistryAuditEvent {
        if (action == null || resultCode == null || objectId == null
                || aggregateVersion < 1 || auditActorRef == null || sourceIp == null
                || traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_AUDIT_EVENT_INVALID");
        }
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(timeSourceProfile);
        if (idempotencyKeyDigest != null
                && !idempotencyKeyDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_AUDIT_DIGEST_INVALID");
        }
    }
}
