package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;

public record RetentionCandidate(String fixtureRecordId, Instant occurredAt, String scope) {
    public RetentionCandidate {
        if (fixtureRecordId == null || fixtureRecordId.isBlank() || occurredAt == null
                || scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("AUDIT_RETENTION_CANDIDATE_INVALID");
        }
    }
}
