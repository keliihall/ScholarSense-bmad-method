package cn.edu.suda.scholarsense.shared.outbox;

public enum AuditIngressOutcome {
    APPENDED,
    EXACT_DUPLICATE,
    REJECTED,
    COLLISION
}
