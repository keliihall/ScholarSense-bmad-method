package cn.edu.suda.scholarsense.auditoperations.application;

@FunctionalInterface
public interface AuditContractRejectionUseCase {
    void reject(AuditContractRejectionCommand rejection);
}
