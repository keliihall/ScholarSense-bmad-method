package cn.edu.suda.scholarsense.auditoperations.domain;

import cn.edu.suda.scholarsense.shared.outbox.LocalAuditFact;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable audit-owned ledger fact; delivery and verification state live elsewhere. */
public record LedgerEntry(
        long ledgerSequence,
        LedgerHash previousHash,
        LedgerHash entryHash,
        String hashProfileVersion,
        UUID auditId,
        UUID sourceEventId,
        String producerModule,
        String eventType,
        String eventSchemaVersion,
        String factSchemaVersion,
        Instant sourceCreatedAt,
        Instant collectedAt,
        String traceId,
        Long aggregateVersion,
        LedgerHash payloadFingerprint,
        LocalAuditFact payload) {
    public LedgerEntry {
        if (ledgerSequence < 1) {
            throw new IllegalArgumentException("AUDIT_LEDGER_SEQUENCE_INVALID");
        }
        Objects.requireNonNull(previousHash, "previousHash");
        Objects.requireNonNull(entryHash, "entryHash");
        require(hashProfileVersion, "AUDIT-LEDGER-HASH-1.0.0", "AUDIT_LEDGER_HASH_PROFILE_INVALID");
        requireUuidV7(auditId, "AUDIT_ID_INVALID");
        requireUuidV7(sourceEventId, "AUDIT_EVENT_ID_INVALID");
        requirePattern(producerModule, "[a-z][a-z0-9-]{2,63}", "AUDIT_LEDGER_PRODUCER_INVALID");
        requirePattern(eventType, "[a-z][a-z0-9.-]{2,127}", "AUDIT_LEDGER_EVENT_TYPE_INVALID");
        require(eventSchemaVersion, "LOCAL-AUDIT-OUTBOX-1.0.0", "AUDIT_LEDGER_EVENT_SCHEMA_INVALID");
        require(factSchemaVersion, "LOCAL-AUDIT-FACT-1.0.0", "AUDIT_LEDGER_FACT_SCHEMA_INVALID");
        Objects.requireNonNull(sourceCreatedAt, "sourceCreatedAt");
        Objects.requireNonNull(collectedAt, "collectedAt");
        if (collectedAt.isBefore(sourceCreatedAt)) {
            throw new IllegalArgumentException("AUDIT_LEDGER_TIME_ORDER_INVALID");
        }
        requirePattern(traceId, "[0-9a-f]{32}", "AUDIT_LEDGER_TRACE_ID_INVALID");
        if (aggregateVersion != null && aggregateVersion < 1) {
            throw new IllegalArgumentException("AUDIT_LEDGER_AGGREGATE_VERSION_INVALID");
        }
        Objects.requireNonNull(payloadFingerprint, "payloadFingerprint");
        Objects.requireNonNull(payload, "payload");
        if (!auditId.equals(payload.auditId())
                || !producerModule.equals(payload.producerModule())
                || !factSchemaVersion.equals(payload.schemaVersion())
                || !traceId.equals(payload.traceId())
                || !Objects.equals(aggregateVersion, payload.aggregateVersion())) {
            throw new IllegalArgumentException("AUDIT_LEDGER_FACT_MISMATCH");
        }
    }

    private static void require(String value, String expected, String code) {
        if (!expected.equals(value)) {
            throw new IllegalArgumentException(code);
        }
    }

    private static void requirePattern(String value, String pattern, String code) {
        if (value == null || !value.matches(pattern)) {
            throw new IllegalArgumentException(code);
        }
    }

    private static void requireUuidV7(UUID value, String code) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new IllegalArgumentException(code);
        }
    }
}
