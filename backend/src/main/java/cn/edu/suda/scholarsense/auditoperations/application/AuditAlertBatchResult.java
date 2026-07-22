package cn.edu.suda.scholarsense.auditoperations.application;

public record AuditAlertBatchResult(int claimed, int confirmed, int retried, int fenced) {}
