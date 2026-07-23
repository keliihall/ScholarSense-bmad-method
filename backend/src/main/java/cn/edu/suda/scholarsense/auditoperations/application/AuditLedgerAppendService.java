package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.FindingCode;
import cn.edu.suda.scholarsense.auditoperations.domain.FindingSeverity;
import cn.edu.suda.scholarsense.auditoperations.domain.IntegrityFinding;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerEntry;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHash;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHead;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/** One independent center transaction: lock head, decide idempotency, append or isolate a collision. */
public final class AuditLedgerAppendService implements AuditLedgerAppendUseCase {
    private final LedgerRepository ledger;
    private final FindingRepository findings;
    private final AlertOutboxPort alerts;
    private final AuditTransactionPort transactions;
    private final AuditClock clock;
    private final AuditPolicyPort policy;
    private final FindingIdPort findingIds;
    private final CanonicalAuditHasher hasher;
    private final AuditSearchProjectionWriter projection;

    public AuditLedgerAppendService(
            LedgerRepository ledger,
            FindingRepository findings,
            AlertOutboxPort alerts,
            AuditTransactionPort transactions,
            AuditClock clock,
            AuditPolicyPort policy,
            FindingIdPort findingIds,
            CanonicalAuditHasher hasher,
            AuditSearchProjectionWriter projection) {
        this.ledger = Objects.requireNonNull(ledger);
        this.findings = Objects.requireNonNull(findings);
        this.alerts = Objects.requireNonNull(alerts);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        this.policy = Objects.requireNonNull(policy);
        this.findingIds = Objects.requireNonNull(findingIds);
        this.hasher = Objects.requireNonNull(hasher);
        this.projection = Objects.requireNonNull(projection);
    }

    @Override
    public AuditAppendResult append(LocalAuditOutboxRecord source) {
        Objects.requireNonNull(source, "source");
        return transactions.required(() -> appendInTransaction(source));
    }

    private AuditAppendResult appendInTransaction(LocalAuditOutboxRecord source) {
        LedgerHead head = ledger.lockHead();
        LedgerHash fingerprint = hasher.payloadFingerprint(source.fact());
        Optional<LedgerEntry> byAudit = ledger.findByAuditId(source.auditId());
        Optional<LedgerEntry> byEvent = ledger.findBySourceEventId(source.eventId());
        if (byAudit.isPresent() || byEvent.isPresent()) {
            LedgerEntry existing = byAudit.orElseGet(byEvent::orElseThrow);
            boolean sameRow = byAudit.map(value -> value.ledgerSequence() == existing.ledgerSequence()).orElse(true)
                    && byEvent.map(value -> value.ledgerSequence() == existing.ledgerSequence()).orElse(true);
            if (sameRow && exactRetry(existing, source, fingerprint)) {
                ledger.recordExactDuplicate(
                        source.auditId(), source.eventId(), clock.now(), source.fact().traceId());
                return new AuditAppendResult(
                        AuditAppendOutcome.EXACT_DUPLICATE,
                        existing.ledgerSequence(),
                        existing.entryHash().value(),
                        null,
                        source.fact().traceId());
            }
            persistCollision(source, fingerprint);
            return new AuditAppendResult(
                    AuditAppendOutcome.COLLISION,
                    null,
                    null,
                    FindingCode.AUDIT_INGESTION_DUPLICATE_CONFLICT.name(),
                    source.fact().traceId());
        }

        Instant collectedAt = clock.now().truncatedTo(ChronoUnit.MICROS);
        long sequence = Math.addExact(head.ledgerSequence(), 1);
        LedgerHash entryHash = hasher.entryHash(sequence, head.entryHash(), source, collectedAt, fingerprint);
        LedgerEntry entry = new LedgerEntry(
                sequence,
                head.entryHash(),
                entryHash,
                policy.hashProfileVersion(),
                source.auditId(),
                source.eventId(),
                source.producer(),
                source.eventType(),
                source.schemaVersion(),
                source.fact().schemaVersion(),
                source.createdAt(),
                collectedAt,
                source.fact().traceId(),
                source.fact().aggregateVersion(),
                fingerprint,
                source.fact());
        ledger.insert(entry);
        projection.project(entry);
        ledger.updateHead(head, new LedgerHead(sequence, entryHash));
        ledger.recordAppended(
                findingIds.newId(collectedAt), source, fingerprint, entry, collectedAt);
        return new AuditAppendResult(
                AuditAppendOutcome.APPENDED, sequence, entryHash.value(), null, source.fact().traceId());
    }

    private static boolean exactRetry(
            LedgerEntry existing, LocalAuditOutboxRecord source, LedgerHash fingerprint) {
        return existing.auditId().equals(source.auditId())
                && existing.sourceEventId().equals(source.eventId())
                && existing.payloadFingerprint().constantTimeEquals(fingerprint)
                && existing.eventSchemaVersion().equals(source.schemaVersion())
                && existing.factSchemaVersion().equals(source.fact().schemaVersion())
                && Objects.equals(existing.aggregateVersion(), source.fact().aggregateVersion());
    }

    private void persistCollision(LocalAuditOutboxRecord source, LedgerHash fingerprint) {
        Instant detectedAt = clock.now();
        LedgerHash safeDigest = hasher.safeDigest(
                source.auditId() + "\0" + source.eventId() + "\0" + fingerprint.value());
        IntegrityFinding finding = new IntegrityFinding(
                findingIds.newId(detectedAt),
                FindingCode.AUDIT_INGESTION_DUPLICATE_CONFLICT,
                FindingSeverity.CRITICAL,
                policy.ingestionPolicyVersion(),
                policy.hashProfileVersion(),
                null,
                null,
                source.producer() + ":" + safeDigest.value().substring(0, 16),
                safeDigest,
                source.fact().traceId(),
                source.createdAt(),
                detectedAt,
                "runbook://audit/ingestion-duplicate-conflict");
        findings.save(finding);
        alerts.enqueueActive(finding);
    }
}
