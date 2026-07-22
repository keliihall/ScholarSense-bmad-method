package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.UUID;

public sealed interface AuditRelayClaim permits ClaimedLocalAudit, RejectedLocalAudit {
    UUID eventId();

    long attempts();
}
