package cn.edu.suda.scholarsense.auditoperations.api;

public enum AuditIngressOutcome {
    APPENDED,
    EXACT_DUPLICATE,
    REJECTED,
    COLLISION
}
