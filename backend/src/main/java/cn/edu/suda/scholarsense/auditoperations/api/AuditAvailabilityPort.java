package cn.edu.suda.scholarsense.auditoperations.api;

@FunctionalInterface
public interface AuditAvailabilityPort {
    AuditAvailabilityResult current(String traceId);
}
