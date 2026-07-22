package cn.edu.suda.scholarsense.auditoperations.application;

public record AuditAppendResult(
        AuditAppendOutcome outcome,
        Long ledgerSequence,
        String entryHash,
        String errorCode,
        String traceId) {}
