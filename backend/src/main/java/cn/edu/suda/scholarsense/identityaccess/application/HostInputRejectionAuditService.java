package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Records only normalized host rejection facts; raw postMessage payloads never cross this port. */
public final class HostInputRejectionAuditService {
    private static final Set<String> CODES = Set.of(
            "HOST_ORIGIN_FORBIDDEN",
            "HOST_SOURCE_FORBIDDEN",
            "HOST_MESSAGE_INVALID",
            "HOST_MESSAGE_REPLAYED");

    private final IdentitySessionRepository sessions;
    private final IdentityAuditFactFactory auditFacts;
    private final IdentityAuditPort audit;

    public HostInputRejectionAuditService(
            IdentitySessionRepository sessions,
            IdentityAuditFactFactory auditFacts,
            IdentityAuditPort audit) {
        this.sessions = sessions;
        this.auditFacts = auditFacts;
        this.audit = audit;
    }

    public void record(
            String internalSessionId,
            String code,
            String sourceIp,
            String traceId) {
        if (!CODES.contains(code)) {
            throw new IdentityAccessException("HOST_MESSAGE_INVALID", "host request is unavailable");
        }
        var session = sessions.findById(internalSessionId).orElseThrow(() ->
                new IdentityAccessException("IDENTITY_SESSION_REQUIRED", "authentication is required"));
        audit.append(auditFacts.create(new IdentityAuditRequest(
                ActorType.USER,
                session.actorPseudonym(),
                List.of(),
                new IdentityAuditAuthorizationContext(
                        "not-applicable", null, List.of(), List.of(), "NO_ROLE_MODEL"),
                IdentityAuditAction.HOST_INPUT_REJECT,
                "rejected",
                code,
                "identity-session",
                session.sessionPseudonym(),
                "HOST_INTEGRATION_SECURITY",
                null,
                sourceIp,
                traceId,
                "identity-session",
                session.sessionPseudonym(),
                session.sessionVersion(),
                null,
                Map.of("hostIntegrationProfile", "HIP-1.0.0"))));
    }
}
