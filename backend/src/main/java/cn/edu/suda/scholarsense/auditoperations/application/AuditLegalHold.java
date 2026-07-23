package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;
import java.util.UUID;

public record AuditLegalHold(
        UUID holdId,
        long version,
        String purpose,
        String scope,
        String authority,
        Instant startAt,
        Instant endAt,
        Instant reviewAt) {
    public AuditLegalHold {
        if (holdId == null || holdId.version() != 7 || holdId.variant() != 2 || version < 1
                || blank(purpose) || blank(scope) || blank(authority)
                || startAt == null || endAt == null || reviewAt == null
                || !startAt.isBefore(endAt)
                || reviewAt.isBefore(startAt) || !reviewAt.isBefore(endAt)) {
            throw new IllegalArgumentException("AUDIT_LEGAL_HOLD_INVALID");
        }
    }

    public boolean appliesTo(String candidateScope, Instant now) {
        return scope.equals(candidateScope) && !now.isBefore(startAt) && now.isBefore(endAt);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
