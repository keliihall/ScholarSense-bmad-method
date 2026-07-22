package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.FindingCode;
import cn.edu.suda.scholarsense.auditoperations.domain.FindingSeverity;
import cn.edu.suda.scholarsense.auditoperations.domain.IntegrityFinding;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerEntry;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHash;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHead;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only verifier. It reports corruption and never rewrites, fills or deletes ledger rows. */
public final class AuditLedgerVerifier {
    private final LedgerRepository ledger;
    private final CanonicalAuditHasher hasher;
    private final FindingRepository findings;
    private final AlertOutboxPort alerts;
    private final FindingIdPort findingIds;
    private final AuditClock clock;
    private final AuditPolicyPort policy;
    private final VerificationRunRepository runs;
    private final AuditVerificationTransactionPort verificationTransactions;
    private final int batchSize;

    public AuditLedgerVerifier(
            LedgerRepository ledger,
            CanonicalAuditHasher hasher,
            FindingRepository findings,
            AlertOutboxPort alerts,
            FindingIdPort findingIds,
            AuditClock clock,
            AuditPolicyPort policy) {
        this(ledger, hasher, findings, alerts, findingIds, clock, policy,
                new VerificationRunRepository() {
                    @Override public void save(VerificationRun run) {}
                    @Override public java.util.Optional<LedgerHead> lastHealthyWatermark() {
                        return java.util.Optional.empty();
                    }
                }, work -> work.get());
    }

    public AuditLedgerVerifier(
            LedgerRepository ledger,
            CanonicalAuditHasher hasher,
            FindingRepository findings,
            AlertOutboxPort alerts,
            FindingIdPort findingIds,
            AuditClock clock,
            AuditPolicyPort policy,
            VerificationRunRepository runs) {
        this(ledger, hasher, findings, alerts, findingIds, clock, policy, runs,
                work -> work.get(), 1_000);
    }

    public AuditLedgerVerifier(
            LedgerRepository ledger,
            CanonicalAuditHasher hasher,
            FindingRepository findings,
            AlertOutboxPort alerts,
            FindingIdPort findingIds,
            AuditClock clock,
            AuditPolicyPort policy,
            VerificationRunRepository runs,
            AuditVerificationTransactionPort verificationTransactions) {
        this(ledger, hasher, findings, alerts, findingIds, clock, policy, runs,
                verificationTransactions, 1_000);
    }

    public AuditLedgerVerifier(
            LedgerRepository ledger,
            CanonicalAuditHasher hasher,
            FindingRepository findings,
            AlertOutboxPort alerts,
            FindingIdPort findingIds,
            AuditClock clock,
            AuditPolicyPort policy,
            VerificationRunRepository runs,
            AuditVerificationTransactionPort verificationTransactions,
            int batchSize) {
        this.ledger = Objects.requireNonNull(ledger);
        this.hasher = Objects.requireNonNull(hasher);
        this.findings = Objects.requireNonNull(findings);
        this.alerts = Objects.requireNonNull(alerts);
        this.findingIds = Objects.requireNonNull(findingIds);
        this.clock = Objects.requireNonNull(clock);
        this.policy = Objects.requireNonNull(policy);
        this.runs = Objects.requireNonNull(runs);
        this.verificationTransactions = Objects.requireNonNull(verificationTransactions);
        if (batchSize < 1) {
            throw new IllegalArgumentException("AUDIT_VERIFICATION_BATCH_INVALID");
        }
        this.batchSize = batchSize;
    }

    public LedgerVerificationResult verifyFull(String traceId) {
        return verificationTransactions.repeatableRead(
                () -> verify(traceId, "full-chain", 1, LedgerHead.genesis()));
    }

    public LedgerVerificationResult verifyIncremental(String traceId) {
        return verificationTransactions.repeatableRead(() -> {
            LedgerHead watermark = runs.lastHealthyWatermark().orElse(LedgerHead.genesis());
            return verify(
                    traceId, "incremental",
                    Math.addExact(watermark.ledgerSequence(), 1), watermark);
        });
    }

    private LedgerVerificationResult verify(
            String traceId, String mode, long startSequence, LedgerHead watermark) {
        requireTrace(traceId);
        Instant startedAt = clock.now();
        Map<FindingCode, Long> anomalies = new EnumMap<>(FindingCode.class);
        long expectedSequence = startSequence;
        long lastSequence = watermark.ledgerSequence();
        LedgerHash expectedPrevious = watermark.entryHash();
        LedgerHash lastHash = watermark.entryHash();
        while (true) {
            List<LedgerEntry> batch;
            try {
                batch = ledger.readFrom(expectedSequence, batchSize);
            } catch (LedgerRepository.ReadCorruptionException corruption) {
                anomalies.putIfAbsent(
                        FindingCode.AUDIT_LEDGER_ENTRY_HASH_MISMATCH,
                        corruption.ledgerSequence());
                break;
            }
            if (batch.isEmpty()) {
                break;
            }
            for (LedgerEntry entry : batch) {
                if (entry.ledgerSequence() != expectedSequence) {
                    anomalies.putIfAbsent(
                            FindingCode.AUDIT_LEDGER_SEQUENCE_GAP, expectedSequence);
                }
                if (!entry.previousHash().constantTimeEquals(expectedPrevious)) {
                    anomalies.putIfAbsent(
                            FindingCode.AUDIT_LEDGER_PREVIOUS_HASH_MISMATCH,
                            entry.ledgerSequence());
                }
                LedgerHash payload = hasher.payloadFingerprint(entry.payload());
                if (!payload.constantTimeEquals(entry.payloadFingerprint())) {
                    anomalies.putIfAbsent(
                            FindingCode.AUDIT_LEDGER_ENTRY_HASH_MISMATCH,
                            entry.ledgerSequence());
                }
                LocalAuditOutboxRecord source = new LocalAuditOutboxRecord(
                        entry.sourceEventId(),
                        entry.auditId(),
                        entry.eventType(),
                        entry.eventSchemaVersion(),
                        entry.producerModule(),
                        entry.sourceCreatedAt(),
                        entry.payload());
                LedgerHash expectedHash = hasher.entryHash(
                        entry.ledgerSequence(), entry.previousHash(), source,
                        entry.collectedAt(), payload);
                if (!expectedHash.constantTimeEquals(entry.entryHash())) {
                    anomalies.putIfAbsent(
                            FindingCode.AUDIT_LEDGER_ENTRY_HASH_MISMATCH,
                            entry.ledgerSequence());
                }
                lastSequence = entry.ledgerSequence();
                lastHash = entry.entryHash();
                expectedPrevious = entry.entryHash();
                expectedSequence = Math.addExact(entry.ledgerSequence(), 1);
            }
            if (batch.size() < batchSize) {
                break;
            }
        }

        LedgerHead head = ledger.readHead();
        if (head.ledgerSequence() != lastSequence || !head.entryHash().constantTimeEquals(lastHash)) {
            anomalies.putIfAbsent(
                    FindingCode.AUDIT_LEDGER_HEAD_MISMATCH,
                    head.ledgerSequence() > 0 ? head.ledgerSequence() : lastSequence);
        }
        if (!anomalies.isEmpty()) {
            persist(anomalies, traceId);
        }
        Instant completedAt = clock.now();
        LedgerVerificationResult result = new LedgerVerificationResult(
                anomalies.isEmpty(), lastSequence, lastHash.value(), Set.copyOf(anomalies.keySet()));
        runs.save(new VerificationRun(
                findingIds.newId(completedAt), mode, startSequence == 1 ? 0 : startSequence - 1,
                lastSequence, new LedgerHead(lastSequence, lastHash), result.healthy(),
                startedAt, completedAt, traceId));
        return result;
    }

    private void persist(Map<FindingCode, Long> anomalies, String traceId) {
        Instant detectedAt = clock.now();
        for (Map.Entry<FindingCode, Long> anomaly : anomalies.entrySet()) {
            FindingCode code = anomaly.getKey();
            long sequence = anomaly.getValue();
            LedgerHash safeDigest = hasher.safeDigest(
                    code.name() + "\0" + sequence + "\0" + traceId);
            Long boundedSequence = sequence < 1 ? null : sequence;
            IntegrityFinding finding = new IntegrityFinding(
                    findingIds.newId(detectedAt),
                    code,
                    FindingSeverity.CRITICAL,
                    policy.ingestionPolicyVersion(),
                    policy.hashProfileVersion(),
                    boundedSequence,
                    boundedSequence,
                    null,
                    safeDigest,
                    traceId,
                    detectedAt,
                    detectedAt,
                    "runbook://audit/" + code.name().toLowerCase().replace('_', '-'));
            findings.save(finding);
            alerts.enqueueActive(finding);
        }
    }

    private static void requireTrace(String traceId) {
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("AUDIT_VERIFICATION_TRACE_INVALID");
        }
    }
}
