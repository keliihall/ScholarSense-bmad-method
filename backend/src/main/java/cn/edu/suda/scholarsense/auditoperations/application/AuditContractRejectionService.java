package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.FindingCode;
import cn.edu.suda.scholarsense.auditoperations.domain.FindingSeverity;
import cn.edu.suda.scholarsense.auditoperations.domain.IntegrityFinding;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHash;
import java.time.Instant;
import java.util.Objects;

/** Persists a quarantine finding before the producer marks an invalid envelope failed. */
public final class AuditContractRejectionService implements AuditContractRejectionUseCase {
    private final FindingRepository findings;
    private final AlertOutboxPort alerts;
    private final AuditTransactionPort transactions;
    private final AuditClock clock;
    private final AuditPolicyPort policy;
    private final FindingIdPort identifiers;

    public AuditContractRejectionService(
            FindingRepository findings,
            AlertOutboxPort alerts,
            AuditTransactionPort transactions,
            AuditClock clock,
            AuditPolicyPort policy,
            FindingIdPort identifiers) {
        this.findings = Objects.requireNonNull(findings);
        this.alerts = Objects.requireNonNull(alerts);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        this.policy = Objects.requireNonNull(policy);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    @Override
    public void reject(AuditContractRejectionCommand rejection) {
        Objects.requireNonNull(rejection);
        transactions.required(() -> {
            Instant detectedAt = clock.now();
            Instant occurredAt = rejection.occurredAt().isAfter(detectedAt)
                    ? detectedAt : rejection.occurredAt();
            IntegrityFinding finding = new IntegrityFinding(
                    identifiers.newId(detectedAt),
                    FindingCode.AUDIT_INGESTION_CONTRACT_REJECTED,
                    FindingSeverity.CRITICAL,
                    policy.ingestionPolicyVersion(),
                    policy.hashProfileVersion(),
                    null,
                    null,
                    rejection.producerModule() + ":" + rejection.safeDigest().substring(0, 16),
                    new LedgerHash(rejection.safeDigest()),
                    rejection.traceId(),
                    occurredAt,
                    detectedAt,
                    "runbook://audit/ingestion-contract-rejected");
            if (findings.saveIfAbsent(finding)) {
                alerts.enqueueActive(finding);
            }
            return null;
        });
    }
}
