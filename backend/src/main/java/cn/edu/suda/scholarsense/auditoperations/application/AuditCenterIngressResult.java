package cn.edu.suda.scholarsense.auditoperations.application;

public record AuditCenterIngressResult(
        AuditCenterIngressOutcome outcome,
        String errorCode,
        boolean retryable) {}
