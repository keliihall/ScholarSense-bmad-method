package cn.edu.suda.scholarsense.auditoperations.application;

public record AuditSearchRelayBatchResult(
        int claimed, int confirmed, int retried, int failed, int fenced) {}
