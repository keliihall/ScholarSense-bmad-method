package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.auditoperations.api.AuditContractRejection;
import java.util.Objects;
import java.util.UUID;

public record RejectedLocalAudit(
        UUID eventId,
        long attempts,
        AuditContractRejection rejection) implements AuditRelayClaim {
    public RejectedLocalAudit {
        Objects.requireNonNull(eventId, "eventId");
        if (eventId.version() != 7 || eventId.variant() != 2 || attempts < 1) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_REJECTED_CLAIM_INVALID");
        }
        Objects.requireNonNull(rejection, "rejection");
    }
}
