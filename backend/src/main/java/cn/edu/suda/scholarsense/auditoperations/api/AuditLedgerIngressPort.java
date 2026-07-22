package cn.edu.suda.scholarsense.auditoperations.api;

import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;

/** Producer-facing transport-neutral center ingestion boundary. */
@FunctionalInterface
public interface AuditLedgerIngressPort {
    AuditIngressResult ingest(LocalAuditOutboxRecord source);

    default AuditIngressResult rejectContract(AuditContractRejection rejection) {
        return AuditIngressResult.rejected(
                "AUDIT_INGESTION_CONTRACT_REJECTED", false, rejection.traceId());
    }
}
