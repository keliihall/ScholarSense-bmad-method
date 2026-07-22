package cn.edu.suda.scholarsense.auditoperations.application;

import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.NOW;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.outbox;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.auditoperations.domain.FindingCode;
import cn.edu.suda.scholarsense.auditoperations.domain.IntegrityFinding;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerEntry;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHash;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHead;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditLedgerVerifierTest {

    @Test
    void fullVerificationRecomputesPayloadEntryPreviousSequenceAndHead() {
        Fixture healthy = new Fixture();
        LedgerEntry entry = healthy.entry(1, LedgerHash.genesis());
        healthy.repository.entries = List.of(entry);
        healthy.repository.head = new LedgerHead(1, entry.entryHash());

        LedgerVerificationResult result = healthy.verifier.verifyFull(outbox().fact().traceId());

        assertTrue(result.healthy());
        assertEquals(1, result.verifiedHeadSequence());
        assertTrue(healthy.findings.isEmpty());
        assertEquals("full-chain", healthy.runs.getFirst().mode());
    }

    @Test
    void fullVerificationRunsInsideTheConfiguredStableSnapshot() {
        int[] snapshots = {0};
        Fixture fixture = new Fixture(work -> {
            snapshots[0]++;
            return work.get();
        });

        fixture.verifier.verifyFull(outbox().fact().traceId());

        assertEquals(1, snapshots[0]);
    }

    @Test
    void incrementalVerificationContinuesFromTheLastHealthyWatermark() {
        Fixture fixture = new Fixture();
        LedgerEntry first = fixture.entry(1, LedgerHash.genesis());
        LedgerEntry second = fixture.entry(2, first.entryHash());
        fixture.repository.entries = List.of(first, second);
        fixture.repository.head = new LedgerHead(2, second.entryHash());
        fixture.watermark = Optional.of(new LedgerHead(1, first.entryHash()));

        LedgerVerificationResult result = fixture.verifier.verifyIncremental(outbox().fact().traceId());

        assertTrue(result.healthy());
        assertEquals(2, result.verifiedHeadSequence());
        assertEquals("incremental", fixture.runs.getFirst().mode());
        assertEquals(1, fixture.runs.getFirst().startSequence());
    }

    @Test
    void verifierPersistsStableFindingsWithoutRepairingTamperedRowsOrHead() {
        Fixture fixture = new Fixture();
        LedgerEntry bad = fixture.entry(2, LedgerHash.genesis());
        bad = new LedgerEntry(
                bad.ledgerSequence(), bad.previousHash(), new LedgerHash("e".repeat(64)),
                bad.hashProfileVersion(), bad.auditId(), bad.sourceEventId(), bad.producerModule(),
                bad.eventType(), bad.eventSchemaVersion(), bad.factSchemaVersion(),
                bad.sourceCreatedAt(), bad.collectedAt(), bad.traceId(), bad.aggregateVersion(),
                new LedgerHash("f".repeat(64)), bad.payload());
        fixture.repository.entries = List.of(bad);
        fixture.repository.head = new LedgerHead(99, new LedgerHash("a".repeat(64)));

        LedgerVerificationResult result = fixture.verifier.verifyFull(outbox().fact().traceId());

        assertTrue(!result.healthy());
        assertTrue(result.findingCodes().contains(FindingCode.AUDIT_LEDGER_SEQUENCE_GAP));
        assertTrue(result.findingCodes().contains(FindingCode.AUDIT_LEDGER_ENTRY_HASH_MISMATCH));
        assertTrue(result.findingCodes().contains(FindingCode.AUDIT_LEDGER_HEAD_MISMATCH));
        assertEquals(fixture.findings, fixture.alerts);
        assertEquals(2, fixture.repository.entries.getFirst().ledgerSequence(), "verifier must not repair");
    }

    @Test
    void undecodableStoredPayloadProducesDurableUnhealthyEvidence() {
        Fixture fixture = new Fixture();
        fixture.repository.readFailure =
                new LedgerRepository.ReadCorruptionException(2, "AUDIT_LEDGER_PAYLOAD_INVALID");
        fixture.repository.head = new LedgerHead(3, new LedgerHash("a".repeat(64)));

        LedgerVerificationResult result = fixture.verifier.verifyFull(outbox().fact().traceId());

        assertTrue(!result.healthy());
        assertTrue(result.findingCodes().contains(FindingCode.AUDIT_LEDGER_ENTRY_HASH_MISMATCH));
        assertTrue(fixture.runs.getFirst().healthy() == false);
        assertEquals(fixture.findings, fixture.alerts);
        assertEquals(2L, fixture.findings.stream()
                .filter(finding -> finding.code() == FindingCode.AUDIT_LEDGER_ENTRY_HASH_MISMATCH)
                .findFirst().orElseThrow().sequenceStart());
    }

    @Test
    void eachFindingKeepsTheSequenceWhereThatSpecificAnomalyWasDetected() {
        Fixture fixture = new Fixture();
        LedgerEntry first = fixture.entry(1, LedgerHash.genesis());
        LedgerEntry badPayload = new LedgerEntry(
                first.ledgerSequence(), first.previousHash(), first.entryHash(),
                first.hashProfileVersion(), first.auditId(), first.sourceEventId(),
                first.producerModule(), first.eventType(), first.eventSchemaVersion(),
                first.factSchemaVersion(), first.sourceCreatedAt(), first.collectedAt(),
                first.traceId(), first.aggregateVersion(), new LedgerHash("f".repeat(64)),
                first.payload());
        LedgerEntry afterGap = fixture.entry(3, first.entryHash());
        fixture.repository.entries = List.of(badPayload, afterGap);
        fixture.repository.head = new LedgerHead(3, afterGap.entryHash());

        fixture.verifier.verifyFull(outbox().fact().traceId());

        assertEquals(1L, fixture.findings.stream()
                .filter(finding -> finding.code() == FindingCode.AUDIT_LEDGER_ENTRY_HASH_MISMATCH)
                .findFirst().orElseThrow().sequenceStart());
        assertEquals(2L, fixture.findings.stream()
                .filter(finding -> finding.code() == FindingCode.AUDIT_LEDGER_SEQUENCE_GAP)
                .findFirst().orElseThrow().sequenceStart());
    }

    private static final class Fixture {
        private final MemoryLedgerRepository repository = new MemoryLedgerRepository();
        private final List<IntegrityFinding> findings = new ArrayList<>();
        private final List<IntegrityFinding> alerts = new ArrayList<>();
        private final CanonicalAuditHasher hasher = new CanonicalAuditHasher();
        private final List<VerificationRun> runs = new ArrayList<>();
        private Optional<LedgerHead> watermark = Optional.empty();
        private final AuditLedgerVerifier verifier;

        private Fixture() {
            this(work -> work.get());
        }

        private Fixture(AuditVerificationTransactionPort verificationTransactions) {
            verifier = new AuditLedgerVerifier(
                    repository,
                    hasher,
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
                ignored -> UUID.fromString("019bf18e-6c00-7000-8000-000000000010"),
                () -> NOW.plusSeconds(15),
                policy(),
                new VerificationRunRepository() {
                    @Override public void save(VerificationRun run) { runs.add(run); }
                    @Override public Optional<LedgerHead> lastHealthyWatermark() { return watermark; }
                    },
                    verificationTransactions);
        }

        private LedgerEntry entry(long sequence, LedgerHash previous) {
            LedgerHash payload = hasher.payloadFingerprint(outbox().fact());
            LedgerHash hash = hasher.entryHash(sequence, previous, outbox(), NOW.plusSeconds(1), payload);
            return new LedgerEntry(
                    sequence, previous, hash, "AUDIT-LEDGER-HASH-1.0.0",
                    outbox().auditId(), outbox().eventId(), outbox().producer(), outbox().eventType(),
                    outbox().schemaVersion(), outbox().fact().schemaVersion(), outbox().createdAt(),
                    NOW.plusSeconds(1), outbox().fact().traceId(), outbox().fact().aggregateVersion(),
                    payload, outbox().fact());
        }

        private static AuditPolicyPort policy() {
            return new AuditPolicyPort() {
                @Override
                public String ingestionPolicyVersion() {
                    return "AUDIT-INGESTION-POLICY-1.0.0";
                }

                @Override
                public String hashProfileVersion() {
                    return "AUDIT-LEDGER-HASH-1.0.0";
                }
            };
        }
    }

    private static final class MemoryLedgerRepository implements LedgerRepository {
        private List<LedgerEntry> entries = List.of();
        private LedgerHead head = LedgerHead.genesis();
        private ReadCorruptionException readFailure;

        @Override
        public Optional<LedgerEntry> findByAuditId(UUID auditId) {
            return entries.stream().filter(entry -> entry.auditId().equals(auditId)).findFirst();
        }

        @Override
        public Optional<LedgerEntry> findBySourceEventId(UUID eventId) {
            return entries.stream().filter(entry -> entry.sourceEventId().equals(eventId)).findFirst();
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
            if (readFailure != null) {
                throw readFailure;
            }
            return entries.stream()
                    .filter(entry -> entry.ledgerSequence() >= startInclusive)
                    .limit(limit)
                    .toList();
        }

        @Override
        public void insert(LedgerEntry entry) {
            throw new AssertionError("verifier must not insert ledger rows");
        }

        @Override
        public void updateHead(LedgerHead expected, LedgerHead replacement) {
            throw new AssertionError("verifier must not update head");
        }
    }
}
