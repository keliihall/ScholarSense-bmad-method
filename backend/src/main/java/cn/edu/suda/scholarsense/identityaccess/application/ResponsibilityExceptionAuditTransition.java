package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.Objects;
import java.util.UUID;

/** A real exception state transition returned by the atomic persistence operation. */
public record ResponsibilityExceptionAuditTransition(
        UUID exceptionId,
        String action,
        String reasonCode,
        long aggregateVersion) {
    public ResponsibilityExceptionAuditTransition {
        Objects.requireNonNull(exceptionId, "exceptionId");
        if (exceptionId.version() != 7
                || exceptionId.variant() != 2
                || !("responsibility.exception.opened".equals(action)
                        || "responsibility.exception.resolved".equals(
                                action))
                || reasonCode == null
                || !reasonCode.matches("RESPONSIBILITY_[A-Z0-9_]+")
                || aggregateVersion < 1) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_EXCEPTION_AUDIT_TRANSITION_INVALID");
        }
    }
}
