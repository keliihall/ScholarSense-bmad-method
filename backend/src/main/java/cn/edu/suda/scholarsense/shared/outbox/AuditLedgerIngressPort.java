package cn.edu.suda.scholarsense.shared.outbox;

/** Producer-facing transport-neutral audit ingestion boundary. */
@FunctionalInterface
public interface AuditLedgerIngressPort {
    AuditIngressResult ingest(LocalAuditOutboxRecord source);

    default AuditIngressResult rejectContract(AuditContractRejection rejection) {
        return AuditIngressResult.rejected(
                "AUDIT_INGESTION_CONTRACT_REJECTED", false, rejection.traceId());
    }
}
