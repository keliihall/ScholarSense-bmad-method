package cn.edu.suda.scholarsense.auditoperations.application;

@FunctionalInterface
public interface AuditAvailabilityUseCase {
    AuditAvailabilityView current(String traceId);
}
