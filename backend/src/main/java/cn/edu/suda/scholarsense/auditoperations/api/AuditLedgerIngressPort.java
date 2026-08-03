package cn.edu.suda.scholarsense.auditoperations.api;

/** Producer-facing transport-neutral center ingestion boundary. */
@FunctionalInterface
public interface AuditLedgerIngressPort
        extends cn.edu.suda.scholarsense.shared.outbox.AuditLedgerIngressPort {}
