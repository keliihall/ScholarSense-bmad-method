package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.identityaccess.domain.RefreshRotation;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SessionRefreshService {
    private final IdentitySessionRepository sessions;
    private final TokenCustodyService tokenCustody;
    private final RemoteIdentityProviderClient identityProvider;
    private final IdentityAuditFactFactory auditFacts;
    private final IdentityAuditPort audit;
    private final RefreshTransactionPort transaction;
    private final Clock clock;

    public SessionRefreshService(
            IdentitySessionRepository sessions,
            TokenCustodyService tokenCustody,
            RemoteIdentityProviderClient identityProvider,
            IdentityAuditFactFactory auditFacts,
            IdentityAuditPort audit,
            RefreshTransactionPort transaction,
            Clock clock) {
        this.sessions = sessions;
        this.tokenCustody = tokenCustody;
        this.identityProvider = identityProvider;
        this.auditFacts = auditFacts;
        this.audit = audit;
        this.transaction = transaction;
        this.clock = clock;
    }

    public RefreshRotation refresh(SessionRefresh input) {
        RefreshRotation rotation;
        AtomicBoolean externalRequestStarted = new AtomicBoolean();
        AtomicBoolean externalRotated = new AtomicBoolean();
        try {
            rotation = transaction.execute(
                    () -> refreshInTransaction(input, externalRequestStarted, externalRotated));
        } catch (IdentityAccessException rejected) {
            if (externalRequestStarted.get()) {
                persistRecoveryRequired(
                        input,
                        externalRotated.get()
                                ? "IDENTITY_LOCAL_COMMIT_FAILED"
                                : "IDENTITY_REMOTE_PROVIDER_UNAVAILABLE");
                throw reauthenticationRequired();
            }
            if (!rejected.code().startsWith("IDENTITY_AUDIT_")) {
                auditRejected(input, rejected.code());
            }
            throw rejected;
        } catch (RuntimeException unavailable) {
            if (externalRequestStarted.get()) {
                persistRecoveryRequired(
                        input,
                        externalRotated.get()
                                ? "IDENTITY_LOCAL_COMMIT_FAILED"
                                : "IDENTITY_REMOTE_PROVIDER_UNAVAILABLE");
                throw reauthenticationRequired();
            }
            auditRejected(input, "IDENTITY_REMOTE_PROVIDER_UNAVAILABLE");
            throw new IdentityAccessException(
                    "IDENTITY_DEPENDENCY_UNAVAILABLE", "identity provider is temporarily unavailable");
        }
        if (rotation.reuseDetected()) {
            throw new IdentityAccessException("IDENTITY_SESSION_EXPIRED", "session is no longer refreshable");
        }
        return rotation;
    }

    public void rejectMissingSession(String sourceIp, String traceId) {
        auditRejected(new SessionRefresh(
                "missing-http-session", 1, "not-applicable", sourceIp, traceId),
                "IDENTITY_SESSION_REQUIRED");
    }

    private RefreshRotation refreshInTransaction(
            SessionRefresh input,
            AtomicBoolean externalRequestStarted,
            AtomicBoolean externalRotated) {
        var current = sessions.findByIdForUpdate(input.sessionId()).orElseThrow(() ->
                new IdentityAccessException("IDENTITY_SESSION_REQUIRED", "authentication is required"));
        var now = clock.instant();
        return tokenCustody.withRefreshToken(input.sessionId(), input.registrationId(), currentRefresh -> {
            String presentedDigest = ContinuationService.digest(currentRefresh);
            if (!current.acceptsRefresh(presentedDigest, input.expectedSessionVersion(), now)) {
                RefreshRotation rejected = current.rotateRefresh(
                        presentedDigest, current.currentRefreshDigest(), input.expectedSessionVersion(), now);
                audit.append(auditFacts.create(request(
                        current, input, "rejected", "IDENTITY_TOKEN_REUSE_DETECTED",
                        rejected.session().sessionVersion())));
                sessions.save(rejected.session());
                return rejected;
            }
            externalRequestStarted.set(true);
            try (RemoteRefreshTokens replacement =
                         identityProvider.refresh(input.registrationId(), currentRefresh)) {
                externalRotated.set(true);
                if (!now.isBefore(replacement.accessExpiresAt())) {
                    throw new IllegalStateException("IDENTITY_REMOTE_REFRESH_INVALID");
                }
                RefreshRotation accepted = current.rotateRefresh(
                        presentedDigest,
                        ContinuationService.digest(replacement.refreshToken()),
                        input.expectedSessionVersion(),
                        now);
                audit.append(auditFacts.create(request(
                        current, input, "accepted", "IDENTITY_SESSION_REFRESHED",
                        accepted.session().sessionVersion())));
                sessions.save(accepted.session());
                tokenCustody.store(
                        input.sessionId(), input.registrationId(), replacement.accessToken(),
                        replacement.refreshToken(), replacement.accessExpiresAt());
                return accepted;
            }
        });
    }

    private void auditRejected(SessionRefresh input, String reasonCode) {
        IdentitySession session = sessions.findById(input.sessionId()).orElse(null);
        audit.append(auditFacts.create(request(
                session, input, "rejected", reasonCode,
                session == null ? null : session.sessionVersion())));
    }

    private void persistRecoveryRequired(SessionRefresh input, String reasonCode) {
        AtomicReference<IdentitySession> persisted = new AtomicReference<>();
        try {
            transaction.executeRecovery(() -> {
                IdentitySession session = sessions.findByIdForUpdate(input.sessionId()).orElse(null);
                if (session == null) {
                    return;
                }
                IdentitySession recovery = session.requireReauthentication(clock.instant());
                sessions.save(recovery);
                persisted.set(recovery);
            });
        } catch (RuntimeException persistenceUnavailable) {
            // The caller still fails closed; deployment recovery must reconcile the ambiguous IdP state.
            return;
        }
        try {
            IdentitySession recovery = persisted.get();
            audit.append(auditFacts.create(request(
                    recovery, input, "rejected", reasonCode,
                    recovery == null ? null : recovery.sessionVersion())));
        } catch (RuntimeException auditUnavailable) {
            // Recovery state is deliberately retained even when a follow-up rejection fact is unavailable.
        }
    }

    private static IdentityAuditRequest request(
            IdentitySession session,
            SessionRefresh input,
            String outcome,
            String reasonCode,
            Long aggregateVersion) {
        boolean anonymous = session == null;
        return new IdentityAuditRequest(
                anonymous ? ActorType.ANONYMOUS : ActorType.USER,
                anonymous ? null : session.actorPseudonym(),
                List.of(),
                new IdentityAuditAuthorizationContext(
                        "not-applicable", null, List.of(), List.of(),
                        anonymous ? "PRE_AUTHENTICATION" : "NO_ROLE_MODEL"),
                IdentityAuditAction.SESSION_REFRESH,
                outcome,
                reasonCode,
                anonymous ? null : "identity-session",
                anonymous ? null : session.sessionPseudonym(),
                "SESSION_CONTINUITY",
                null,
                input.sourceIp(),
                input.traceId(),
                anonymous ? null : "identity-session",
                anonymous ? null : session.sessionPseudonym(),
                aggregateVersion,
                null,
                Map.of("identitySessionPolicy", "ISP-1.0.0"));
    }

    private static IdentityAccessException reauthenticationRequired() {
        return new IdentityAccessException(
                "IDENTITY_REAUTHENTICATION_REQUIRED",
                "session recovery requires authentication");
    }
}
