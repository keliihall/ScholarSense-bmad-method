package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;
import java.util.List;

public record SearchAuditEvent(
        String requesterKey,
        String action,
        String outcome,
        String errorCode,
        List<String> filterTypes,
        String filterDigest,
        Long asOfSequence,
        String traceId,
        Instant occurredAt) {
    public SearchAuditEvent {
        filterTypes = List.copyOf(filterTypes);
    }
}
