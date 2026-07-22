package cn.edu.suda.scholarsense.auditoperations.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Privacy-safe integrity evidence. Raw actors, objects, payloads, IPs and student identifiers have no fields here. */
public record IntegrityFinding(
        UUID findingId,
        FindingCode code,
        FindingSeverity severity,
        String policyVersion,
        String hashProfileVersion,
        Long sequenceStart,
        Long sequenceEnd,
        String sourceRange,
        LedgerHash safeDigest,
        String traceId,
        Instant occurredAt,
        Instant detectedAt,
        String runbookRef) {
    public IntegrityFinding {
        requireUuidV7(findingId);
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        if (!"AUDIT-INGESTION-POLICY-1.0.0".equals(policyVersion)) {
            throw new IllegalArgumentException("AUDIT_FINDING_POLICY_INVALID");
        }
        if (!"AUDIT-LEDGER-HASH-1.0.0".equals(hashProfileVersion)) {
            throw new IllegalArgumentException("AUDIT_FINDING_HASH_PROFILE_INVALID");
        }
        if ((sequenceStart == null) != (sequenceEnd == null)
                || sequenceStart != null && (sequenceStart < 1 || sequenceEnd < sequenceStart)) {
            throw new IllegalArgumentException("AUDIT_FINDING_SEQUENCE_RANGE_INVALID");
        }
        if (sourceRange != null && !sourceRange.matches("[a-z0-9-]{3,64}:[0-9a-f]{16,64}")) {
            throw new IllegalArgumentException("AUDIT_FINDING_SOURCE_RANGE_INVALID");
        }
        Objects.requireNonNull(safeDigest, "safeDigest");
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("AUDIT_FINDING_TRACE_INVALID");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(detectedAt, "detectedAt");
        if (detectedAt.isBefore(occurredAt)) {
            throw new IllegalArgumentException("AUDIT_FINDING_TIME_ORDER_INVALID");
        }
        if (runbookRef == null || !runbookRef.matches("runbook://audit/[a-z0-9-]{3,80}")) {
            throw new IllegalArgumentException("AUDIT_FINDING_RUNBOOK_INVALID");
        }
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new IllegalArgumentException("AUDIT_FINDING_ID_INVALID");
        }
    }
}
