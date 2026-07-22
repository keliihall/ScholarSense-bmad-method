package cn.edu.suda.scholarsense.auditoperations.application;

@FunctionalInterface
public interface AuditBacklogMeasurementPort {
    AuditBacklogMeasurement current();
}
