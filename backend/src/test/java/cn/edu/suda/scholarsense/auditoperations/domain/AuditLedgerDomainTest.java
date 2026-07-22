package cn.edu.suda.scholarsense.auditoperations.domain;

import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.AUDIT_ID;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.EVENT_ID;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.NOW;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.TRACE_ID;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.fact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AuditLedgerDomainTest {

    @Test
    void ledgerHashRequiresCanonicalLowercaseSha256AndComparesInConstantTime() {
        LedgerHash hash = new LedgerHash("a".repeat(64));
        assertTrue(hash.constantTimeEquals(new LedgerHash("a".repeat(64))));
        assertFalse(hash.constantTimeEquals(new LedgerHash("b".repeat(64))));
        assertEquals("0".repeat(64), LedgerHash.genesis().value());
        assertThrows(IllegalArgumentException.class, () -> new LedgerHash("A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new LedgerHash("short"));
    }

    @Test
    void ledgerEntryBindsVersionsIdentifiersTrustedTimesTraceAndCompletePayload() {
        LedgerEntry entry = new LedgerEntry(
                1,
                LedgerHash.genesis(),
                new LedgerHash("e".repeat(64)),
                "AUDIT-LEDGER-HASH-1.0.0",
                AUDIT_ID,
                EVENT_ID,
                "identity-access",
                "identity-access.local-audit-fact.recorded.v1",
                "LOCAL-AUDIT-OUTBOX-1.0.0",
                "LOCAL-AUDIT-FACT-1.0.0",
                NOW,
                NOW.plusSeconds(1),
                TRACE_ID,
                2L,
                new LedgerHash("f".repeat(64)),
                fact());

        assertEquals(1, entry.ledgerSequence());
        assertThrows(IllegalArgumentException.class, () -> new LedgerEntry(
                0, entry.previousHash(), entry.entryHash(), entry.hashProfileVersion(),
                entry.auditId(), entry.sourceEventId(), entry.producerModule(), entry.eventType(),
                entry.eventSchemaVersion(), entry.factSchemaVersion(), entry.sourceCreatedAt(),
                entry.collectedAt(), entry.traceId(), entry.aggregateVersion(),
                entry.payloadFingerprint(), entry.payload()));
        assertThrows(IllegalArgumentException.class, () -> new LedgerEntry(
                1, entry.previousHash(), entry.entryHash(), "AUDIT-LEDGER-HASH-2.0.0",
                entry.auditId(), entry.sourceEventId(), entry.producerModule(), entry.eventType(),
                entry.eventSchemaVersion(), entry.factSchemaVersion(), entry.sourceCreatedAt(),
                entry.collectedAt(), entry.traceId(), entry.aggregateVersion(),
                entry.payloadFingerprint(), entry.payload()));
    }

    @Test
    void typedFindingAndAvailabilityCannotCarryRawStudentEvidence() {
        IntegrityFinding finding = new IntegrityFinding(
                EVENT_ID,
                FindingCode.AUDIT_LEDGER_ENTRY_HASH_MISMATCH,
                FindingSeverity.CRITICAL,
                "AUDIT-INGESTION-POLICY-1.0.0",
                "AUDIT-LEDGER-HASH-1.0.0",
                41L,
                41L,
                "identity-access:0c97b5e3a80f1ec2",
                new LedgerHash("a".repeat(64)),
                TRACE_ID,
                NOW,
                NOW.plusSeconds(15),
                "runbook://audit/ledger-entry-hash-mismatch");
        assertEquals(FindingSeverity.CRITICAL, finding.severity());

        AuditAvailability availability = new AuditAvailability(
                AvailabilityState.BLOCKED,
                "AUDIT-INGESTION-POLICY-1.0.0",
                Set.of(FindingCode.AUDIT_LEDGER_ENTRY_HASH_MISMATCH),
                NOW,
                NOW.plusSeconds(45),
                TRACE_ID);
        assertTrue(availability.isFreshAt(NOW.plusSeconds(45)));
        assertFalse(availability.isFreshAt(NOW.plusSeconds(46)));
    }
}
