package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;
import java.util.Set;

public record AuditAvailabilityView(
        String state,
        String policyVersion,
        Set<String> reasonCodes,
        Instant observedAt,
        Instant freshUntil) {
    public AuditAvailabilityView {
        reasonCodes = Set.copyOf(reasonCodes);
    }
}
