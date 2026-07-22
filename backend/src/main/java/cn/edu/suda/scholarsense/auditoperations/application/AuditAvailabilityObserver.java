package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.AuditAvailability;

@FunctionalInterface
public interface AuditAvailabilityObserver {
    void observe(AuditAvailability availability, AuditBacklogMeasurement measurement);
}
