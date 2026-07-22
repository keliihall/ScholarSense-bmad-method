package cn.edu.suda.scholarsense.auditoperations.api;

import cn.edu.suda.scholarsense.auditoperations.application.AuditAppendResult;
import cn.edu.suda.scholarsense.auditoperations.application.AuditContractRejectionCommand;
import cn.edu.suda.scholarsense.auditoperations.application.AuditLedgerAppendUseCase;
import cn.edu.suda.scholarsense.auditoperations.application.LowCardinalityAuditMetrics;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import java.util.Map;
import java.util.Objects;

/** Public facade keeps application and storage types out of producer modules. */
public final class AuditLedgerIngress implements AuditLedgerIngressPort {
    private final AuditLedgerAppendUseCase useCase;
    private final cn.edu.suda.scholarsense.auditoperations.application.AuditContractRejectionUseCase
            contractRejections;
    private final LowCardinalityAuditMetrics metrics;

    public AuditLedgerIngress(AuditLedgerAppendUseCase useCase) {
        this(useCase, rejection -> {});
    }

    public AuditLedgerIngress(
            AuditLedgerAppendUseCase useCase,
            cn.edu.suda.scholarsense.auditoperations.application.AuditContractRejectionUseCase
                    contractRejections) {
        this(useCase, contractRejections, new LowCardinalityAuditMetrics((name, labels) -> {}));
    }

    public AuditLedgerIngress(
            AuditLedgerAppendUseCase useCase,
            cn.edu.suda.scholarsense.auditoperations.application.AuditContractRejectionUseCase
                    contractRejections,
            LowCardinalityAuditMetrics metrics) {
        this.useCase = Objects.requireNonNull(useCase);
        this.contractRejections = Objects.requireNonNull(contractRejections);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public AuditIngressResult ingest(LocalAuditOutboxRecord source) {
        AuditAppendResult result = useCase.append(source);
        metrics.record("audit.ingestion.outcome", Map.of(
                "outcome", switch (result.outcome()) {
                    case APPENDED -> "appended";
                    case EXACT_DUPLICATE -> "duplicate";
                    case COLLISION -> "collision";
                }));
        return switch (result.outcome()) {
            case APPENDED -> AuditIngressResult.appended(
                    result.ledgerSequence(), result.entryHash(), result.traceId());
            case EXACT_DUPLICATE -> AuditIngressResult.exactDuplicate(
                    result.ledgerSequence(), result.entryHash(), result.traceId());
            case COLLISION -> AuditIngressResult.collision(result.traceId());
        };
    }

    @Override
    public AuditIngressResult rejectContract(AuditContractRejection rejection) {
        contractRejections.reject(new AuditContractRejectionCommand(
                rejection.producerModule(), rejection.safeDigest(), rejection.traceId(),
                rejection.occurredAt()));
        metrics.record("audit.ingestion.outcome", Map.of("outcome", "rejected"));
        return AuditIngressResult.rejected(
                "AUDIT_INGESTION_CONTRACT_REJECTED", false, rejection.traceId());
    }
}
