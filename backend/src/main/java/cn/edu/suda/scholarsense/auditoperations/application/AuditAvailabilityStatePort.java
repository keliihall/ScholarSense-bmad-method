package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.Optional;

@FunctionalInterface
public interface AuditAvailabilityStatePort {
    Optional<AuditAvailabilityState> latest();
}
