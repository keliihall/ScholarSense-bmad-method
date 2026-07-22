package cn.edu.suda.scholarsense.auditoperations.application;

import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.EVENT_ID;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.NOW;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.fact;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.outbox;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.edu.suda.scholarsense.auditoperations.domain.IntegrityFinding;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerEntry;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHead;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class AuditLedgerAppendServiceTest {

    @Test
    void appendStartsAtOneAndExactRetryReturnsOriginalWithoutConsumingSequence() {
        Fixture fixture = new Fixture();

        AuditAppendResult first = fixture.service.append(outbox());
        AuditAppendResult retry = fixture.service.append(outbox());

        assertEquals(AuditAppendOutcome.APPENDED, first.outcome());
        assertEquals(1, first.ledgerSequence());
        assertEquals(AuditAppendOutcome.EXACT_DUPLICATE, retry.outcome());
        assertEquals(first.ledgerSequence(), retry.ledgerSequence());
        assertEquals(first.entryHash(), retry.entryHash());
        assertEquals(1, fixture.repository.entries.size());
        assertEquals(1, fixture.repository.head.ledgerSequence());
        assertEquals(2, fixture.transactions);
        assertEquals(1, fixture.repository.duplicateObservations);
    }

    @Test
    void sameIdentifiersWithDifferentAggregateVersionAreQuarantinedAsCriticalCollision() {
        Fixture fixture = new Fixture();
        fixture.service.append(outbox());
        LocalAuditOutboxRecord conflict = LocalAuditOutboxRecord.forFact(EVENT_ID, fact(3L, Map.of(
                "identitySessionPolicy", "ISP-1.0.0")), NOW);

        AuditAppendResult result = fixture.service.append(conflict);

        assertEquals(AuditAppendOutcome.COLLISION, result.outcome());
        assertEquals("AUDIT_INGESTION_DUPLICATE_CONFLICT", result.errorCode());
        assertNull(result.ledgerSequence());
        assertEquals(1, fixture.repository.entries.size());
        assertEquals(1, fixture.findings.size());
        assertEquals(fixture.findings, fixture.alerts);
    }

    @Test
    void collectedAtIsNormalizedBeforeHashingToPostgresqlMicrosecondPrecision() {
        Instant nanosecondClock = NOW.plusSeconds(1).plusNanos(789);
        Fixture fixture = new Fixture(nanosecondClock);

        fixture.service.append(outbox());

        assertEquals(
                nanosecondClock.truncatedTo(ChronoUnit.MICROS),
                fixture.repository.entries.getFirst().collectedAt());
    }

    private static final class Fixture {
        private final MemoryLedgerRepository repository = new MemoryLedgerRepository();
        private final List<IntegrityFinding> findings = new ArrayList<>();
        private final List<IntegrityFinding> alerts = new ArrayList<>();
        private int transactions;
        private final AuditLedgerAppendService service;

        private Fixture() {
            this(NOW.plusSeconds(1));
        }

        private Fixture(Instant collectedAt) {
            service = new AuditLedgerAppendService(
                repository,
                new FindingRepository() {
                    @Override
                    public void save(IntegrityFinding finding) {
                        findings.add(finding);
                    }

                    @Override
                    public boolean hasActivePermanentFinding() {
                        return !findings.isEmpty();
                    }

                    @Override
                    public List<IntegrityFinding> activeFindings() {
                        return List.copyOf(findings);
                    }
                },
                alerts::add,
                new AuditTransactionPort() {
                    @Override
                    public <T> T required(java.util.function.Supplier<T> work) {
                        transactions++;
                        return work.get();
                    }
                },
                () -> collectedAt,
                new AuditPolicyPort() {
                    @Override
                    public String ingestionPolicyVersion() {
                        return "AUDIT-INGESTION-POLICY-1.0.0";
                    }

                    @Override
                    public String hashProfileVersion() {
                        return "AUDIT-LEDGER-HASH-1.0.0";
                    }
                },
                ignored -> UUID.fromString("019bf18e-6c00-7000-8000-000000000010"),
                new CanonicalAuditHasher());
        }
    }

    private static final class MemoryLedgerRepository implements LedgerRepository {
        private final Map<UUID, LedgerEntry> byAudit = new LinkedHashMap<>();
        private final Map<UUID, LedgerEntry> byEvent = new LinkedHashMap<>();
        private final List<LedgerEntry> entries = new ArrayList<>();
        private LedgerHead head = LedgerHead.genesis();
        private int duplicateObservations;

        @Override
        public Optional<LedgerEntry> findByAuditId(UUID auditId) {
            return Optional.ofNullable(byAudit.get(auditId));
        }

        @Override
        public Optional<LedgerEntry> findBySourceEventId(UUID sourceEventId) {
            return Optional.ofNullable(byEvent.get(sourceEventId));
        }

        @Override
        public LedgerHead lockHead() {
            return head;
        }

        @Override
        public LedgerHead readHead() {
            return head;
        }

        @Override
        public List<LedgerEntry> readFrom(long startInclusive, int limit) {
            return entries.stream()
                    .filter(entry -> entry.ledgerSequence() >= startInclusive)
                    .limit(limit)
                    .toList();
        }

        @Override
        public void insert(LedgerEntry entry) {
            entries.add(entry);
            byAudit.put(entry.auditId(), entry);
            byEvent.put(entry.sourceEventId(), entry);
        }

        @Override
        public void updateHead(LedgerHead expected, LedgerHead replacement) {
            assertEquals(expected, head);
            head = replacement;
        }

        @Override
        public void recordExactDuplicate(
                UUID auditId, UUID sourceEventId, Instant observedAt, String traceId) {
            duplicateObservations++;
        }
    }
}
