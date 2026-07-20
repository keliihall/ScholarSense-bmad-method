package cn.edu.suda.scholarsense.shared.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LocalAuditOutboxRecord(
        UUID eventId,
        UUID auditId,
        String eventType,
        String schemaVersion,
        String producer,
        Instant createdAt,
        LocalAuditFact fact) {
    public LocalAuditOutboxRecord {
        LocalAuditFact.requireUuidV7(eventId, "AUDIT_EVENT_ID_INVALID");
        LocalAuditFact.requireUuidV7(auditId, "AUDIT_ID_INVALID");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(fact, "fact");
        if (!auditId.equals(fact.auditId())) {
            throw new IllegalArgumentException("AUDIT_OUTBOX_FACT_MISMATCH");
        }
        String expectedEventType = fact.producerModule() + ".local-audit-fact.recorded.v1";
        if (!expectedEventType.equals(eventType)
                || !"LOCAL-AUDIT-OUTBOX-1.0.0".equals(schemaVersion)
                || !fact.producerModule().equals(producer)) {
            throw new IllegalArgumentException("AUDIT_OUTBOX_CONTRACT_INVALID");
        }
    }

    public static LocalAuditOutboxRecord forFact(UUID eventId, LocalAuditFact fact, Instant createdAt) {
        return new LocalAuditOutboxRecord(
                eventId,
                fact.auditId(),
                fact.producerModule() + ".local-audit-fact.recorded.v1",
                "LOCAL-AUDIT-OUTBOX-1.0.0",
                fact.producerModule(),
                createdAt,
                fact);
    }
}
