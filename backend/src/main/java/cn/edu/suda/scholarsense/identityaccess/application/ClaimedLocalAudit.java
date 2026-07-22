package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;

public record ClaimedLocalAudit(LocalAuditOutboxRecord source, long attempts)
        implements AuditRelayClaim {
    public ClaimedLocalAudit {
        if (source == null || attempts < 1) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_CLAIM_INVALID");
        }
    }

    @Override
    public java.util.UUID eventId() {
        return source.eventId();
    }
}
