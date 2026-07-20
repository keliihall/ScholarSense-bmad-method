package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import java.time.Clock;
import java.util.List;
import java.util.Map;

public final class CurrentSessionService {
    private final IdentitySessionRepository sessions;
    private final AuthorizationRecalculationPort authorization;
    private final IdentityAuditFactFactory auditFacts;
    private final IdentityAuditPort audit;
    private final SensitiveReadTransactionPort transaction;
    private final Clock clock;

    public CurrentSessionService(
            IdentitySessionRepository sessions,
            AuthorizationRecalculationPort authorization,
            IdentityAuditFactFactory auditFacts,
            IdentityAuditPort audit,
            SensitiveReadTransactionPort transaction,
            Clock clock) {
        this.sessions = sessions;
        this.authorization = authorization;
        this.auditFacts = auditFacts;
        this.audit = audit;
        this.transaction = transaction;
        this.clock = clock;
    }

    public CurrentSessionProjection current(String internalSessionId, String sourceIp, String traceId) {
        ReadOutcome outcome = transaction.execute(
                () -> currentInTransaction(internalSessionId, sourceIp, traceId));
        if (outcome.failure() != null) {
            throw outcome.failure();
        }
        return outcome.projection();
    }

    private ReadOutcome currentInTransaction(String internalSessionId, String sourceIp, String traceId) {
        var session = sessions.findById(internalSessionId).orElse(null);
        if (session == null) {
            audit.append(auditFacts.create(request(
                    null, ActorType.ANONYMOUS,
                    new IdentityAuditAuthorizationContext(
                            "not-applicable", null, List.of(), List.of(), "PRE_AUTHENTICATION"),
                    "rejected", "IDENTITY_SESSION_REQUIRED", sourceIp, traceId)));
            return ReadOutcome.failure(required());
        }
        if (!session.activeAt(clock.instant())) {
            audit.append(auditFacts.create(request(
                    session, ActorType.USER, denied(),
                    "rejected", "IDENTITY_SESSION_EXPIRED", sourceIp, traceId)));
            return ReadOutcome.failure(new IdentityAccessException(
                    "IDENTITY_SESSION_EXPIRED", "authentication is required"));
        }
        if (!authorization.isCurrentSessionAllowed(
                session.actorPseudonym(), session.sessionPseudonym())) {
            audit.append(auditFacts.create(request(
                    session, ActorType.USER, denied(),
                    "rejected", "IDENTITY_SESSION_REQUIRED", sourceIp, traceId)));
            return ReadOutcome.failure(required());
        }
        var expiresAt = session.idleExpiresAt().isBefore(session.absoluteExpiresAt())
                ? session.idleExpiresAt() : session.absoluteExpiresAt();
        CurrentSessionProjection projection = new CurrentSessionProjection(
                true,
                session.sessionPseudonym(),
                session.sessionVersion(),
                expiresAt,
                session.warningAt(),
                "ISP-1.0.0");
        audit.append(auditFacts.create(request(
                session,
                ActorType.USER,
                new IdentityAuditAuthorizationContext(
                        "allow", "ISP-1.0.0", List.of("CURRENT_SESSION"), List.of(), null),
                "accepted",
                "AUTHORIZATION_ALLOWED",
                sourceIp,
                traceId)));
        return ReadOutcome.success(projection);
    }

    private static IdentityAccessException required() {
        return new IdentityAccessException("IDENTITY_SESSION_REQUIRED", "authentication is required");
    }

    private static IdentityAuditAuthorizationContext denied() {
        return new IdentityAuditAuthorizationContext(
                "deny", "ISP-1.0.0", List.of("CURRENT_SESSION"), List.of(), null);
    }

    private static IdentityAuditRequest request(
            IdentitySession session,
            ActorType actorType,
            IdentityAuditAuthorizationContext authorization,
            String outcome,
            String reasonCode,
            String sourceIp,
            String traceId) {
        String sessionPseudonym = session == null ? null : session.sessionPseudonym();
        return new IdentityAuditRequest(
                actorType,
                session == null ? null : session.actorPseudonym(),
                List.of(),
                authorization,
                IdentityAuditAction.SESSION_VIEW,
                outcome,
                reasonCode,
                session == null ? null : "identity-session",
                sessionPseudonym,
                "SESSION_CONTINUITY",
                "CURRENT_SESSION",
                sourceIp,
                traceId,
                session == null ? null : "identity-session",
                sessionPseudonym,
                session == null ? null : session.sessionVersion(),
                null,
                Map.of("identitySessionPolicy", "ISP-1.0.0"));
    }

    private record ReadOutcome(
            CurrentSessionProjection projection,
            IdentityAccessException failure) {
        static ReadOutcome success(CurrentSessionProjection projection) {
            return new ReadOutcome(projection, null);
        }

        static ReadOutcome failure(IdentityAccessException failure) {
            return new ReadOutcome(null, failure);
        }
    }
}
