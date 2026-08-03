package cn.edu.suda.scholarsense.shared.time;

@FunctionalInterface
public interface AuditAvailabilityPort {
    AuditAvailabilityResult current(String traceId);
}
