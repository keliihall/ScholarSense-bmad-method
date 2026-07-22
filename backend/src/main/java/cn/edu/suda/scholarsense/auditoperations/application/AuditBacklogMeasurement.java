package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;
import java.util.Objects;

public record AuditBacklogMeasurement(
        long unconfirmedCount,
        long oldestUnconfirmedAgeSeconds,
        boolean permanentFindingActive,
        boolean chainHealthy,
        Instant measuredAt,
        boolean available) {
    public AuditBacklogMeasurement {
        if (unconfirmedCount < 0 || oldestUnconfirmedAgeSeconds < 0) {
            throw new IllegalArgumentException("AUDIT_BACKLOG_MEASUREMENT_INVALID");
        }
        Objects.requireNonNull(measuredAt, "measuredAt");
    }
}
