package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SessionCommandService {
    private final IdentitySessionRepository sessions;
    private final SessionIdempotencyRepository idempotency;
    private final IdentityAuditFactFactory auditFacts;
    private final IdentityAuditPort audit;
    private final RemoteLogoutOutboxPort remoteLogout;
    private final SessionTransactionPort transaction;
    private final Clock clock;
    private final String registrationId;
    private final HighRiskOperationGuard auditAvailability;

    public SessionCommandService(
            IdentitySessionRepository sessions,
            SessionIdempotencyRepository idempotency,
            IdentityAuditFactFactory auditFacts,
            IdentityAuditPort audit,
            RemoteLogoutOutboxPort remoteLogout,
            SessionTransactionPort transaction,
            Clock clock) {
        this(sessions, idempotency, auditFacts, audit, remoteLogout, transaction, clock,
                "school-idp", traceId -> {});
    }

    public SessionCommandService(
            IdentitySessionRepository sessions,
            SessionIdempotencyRepository idempotency,
            IdentityAuditFactFactory auditFacts,
            IdentityAuditPort audit,
            RemoteLogoutOutboxPort remoteLogout,
            SessionTransactionPort transaction,
            Clock clock,
            String registrationId) {
        this(sessions, idempotency, auditFacts, audit, remoteLogout, transaction, clock,
                registrationId, traceId -> {});
    }

    public SessionCommandService(
            IdentitySessionRepository sessions,
            SessionIdempotencyRepository idempotency,
            IdentityAuditFactFactory auditFacts,
            IdentityAuditPort audit,
            RemoteLogoutOutboxPort remoteLogout,
            SessionTransactionPort transaction,
            Clock clock,
            String registrationId,
            HighRiskOperationGuard auditAvailability) {
        this.sessions = sessions;
        this.idempotency = idempotency;
        this.auditFacts = auditFacts;
        this.audit = audit;
        this.remoteLogout = remoteLogout;
        this.transaction = transaction;
        this.clock = clock;
        this.auditAvailability = java.util.Objects.requireNonNull(auditAvailability, "auditAvailability");
        if (registrationId == null || !registrationId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("IDENTITY_REGISTRATION_ID_INVALID");
        }
        this.registrationId = registrationId;
    }

    public SessionCommandResult execute(SessionCommand command) {
        auditAvailability.requireAvailable(command.traceId());
        try {
            return transaction.execute(() -> executeInTransaction(command));
        } catch (IdentityAccessException rejected) {
            if ("IDENTITY_SESSION_VERSION_CONFLICT".equals(rejected.code())) {
                StoredSessionCommand committed = idempotency.find(
                        command.sessionId(), command.commandType(), command.idempotencyKey()).orElse(null);
                if (committed != null && committed.requestDigest().equals(command.requestDigest())) {
                    return committed.result();
                }
            }
            if ("IDENTITY_SESSION_VERSION_CONFLICT".equals(rejected.code())
                    || "IDENTITY_IDEMPOTENCY_MISMATCH".equals(rejected.code())
                    || "IDENTITY_SESSION_REQUIRED".equals(rejected.code())) {
                appendRejection(
                        command.sessionId(), command.commandType(), rejected.code(),
                        command.sourceIp(), command.traceId(), command.idempotencyKey());
            }
            throw rejected;
        }
    }

    /** Replays only a previously committed result; it never executes a new unauthenticated command. */
    public Optional<SessionCommandResult> replayCompleted(
            SessionCommandType type,
            String idempotencyKey,
            String requestDigest,
            String sourceIp,
            String traceId) {
        auditAvailability.requireAvailable(traceId);
        if (!SessionCommand.isIdempotencyKeyValid(idempotencyKey)) {
            auditAnonymousRejection(type, idempotencyKey, sourceIp, traceId);
            return Optional.empty();
        }
        StoredSessionCommand replay = idempotency.find(type, idempotencyKey).orElse(null);
        if (replay == null) {
            appendRejection(
                    null, type, "IDENTITY_SESSION_REQUIRED", sourceIp, traceId, idempotencyKey);
            return Optional.empty();
        }
        if (!replay.requestDigest().equals(requestDigest)) {
            appendRejection(
                    replay.sessionId(), type, "IDENTITY_IDEMPOTENCY_MISMATCH",
                    sourceIp, traceId, idempotencyKey);
            throw new IdentityAccessException(
                    "IDENTITY_IDEMPOTENCY_MISMATCH",
                    "idempotency key was used for another request");
        }
        return Optional.of(replay.result());
    }

    /** Records a command rejected by an inbound security filter before controller dispatch. */
    public void auditAnonymousRejection(
            SessionCommandType type,
            String idempotencyKey,
            String sourceIp,
            String traceId) {
        appendRejection(
                null, type, "IDENTITY_SESSION_REQUIRED", sourceIp, traceId, idempotencyKey);
    }

    private SessionCommandResult executeInTransaction(SessionCommand command) {
        StoredSessionCommand replay = idempotency.find(
                command.sessionId(), command.commandType(), command.idempotencyKey()).orElse(null);
        if (replay != null) {
            if (!replay.requestDigest().equals(command.requestDigest())) {
                throw new IdentityAccessException(
                        "IDENTITY_IDEMPOTENCY_MISMATCH", "idempotency key was used for another request");
            }
            return replay.result();
        }

        IdentitySession current = sessions.findById(command.sessionId()).orElseThrow(() ->
                new IdentityAccessException("IDENTITY_SESSION_REQUIRED", "authentication is required"));
        Instant now = clock.instant();
        IdentitySession revoked = current.revoke(command.expectedSessionVersion(), now);
        audit.append(auditFacts.create(request(
                current,
                command.commandType(),
                "accepted",
                command.commandType() == SessionCommandType.LOGOUT
                        ? "IDENTITY_SESSION_LOGGED_OUT" : "IDENTITY_SESSION_ACCOUNT_SWITCHED",
                command.sourceIp(),
                command.traceId(),
                revoked.sessionVersion(),
                command.idempotencyKey())));

        SessionCommandResult result = new SessionCommandResult(
                current.sessionPseudonym(), command.commandType(), revoked.sessionVersion(), now,
                "/scholarsense/", true);
        sessions.save(revoked);
        remoteLogout.enqueue(new RemoteLogoutRequest(
                UuidV7.generate(now), command.sessionId(), registrationId, command.commandType(),
                command.idempotencyKey(), now, "pending"));
        idempotency.save(new StoredSessionCommand(
                command.sessionId(), command.commandType(), command.idempotencyKey(),
                command.requestDigest(), result));
        return result;
    }

    private void appendRejection(
            String sessionId,
            SessionCommandType type,
            String code,
            String sourceIp,
            String traceId,
            String idempotencyKey) {
        IdentitySession session = sessionId == null
                ? null
                : sessions.findById(sessionId).orElse(null);
        audit.append(auditFacts.create(request(
                session,
                type,
                "rejected",
                code,
                sourceIp,
                traceId,
                session == null ? null : session.sessionVersion(),
                idempotencyKey)));
    }

    private static IdentityAuditRequest request(
            IdentitySession session,
            SessionCommandType type,
            String outcome,
            String reasonCode,
            String sourceIp,
            String traceId,
            Long aggregateVersion,
            String idempotencyKey) {
        String sessionPseudonym = session == null ? null : session.sessionPseudonym();
        return new IdentityAuditRequest(
                session == null ? ActorType.ANONYMOUS : ActorType.USER,
                session == null ? null : session.actorPseudonym(),
                List.of(),
                new IdentityAuditAuthorizationContext(
                        "not-applicable", null, List.of(), List.of(),
                        session == null ? "PRE_AUTHENTICATION" : "NO_ROLE_MODEL"),
                type == SessionCommandType.LOGOUT
                        ? IdentityAuditAction.SESSION_LOGOUT
                        : IdentityAuditAction.SESSION_ACCOUNT_SWITCH,
                outcome,
                reasonCode,
                session == null ? null : "identity-session",
                sessionPseudonym,
                "SESSION_CONTROL",
                null,
                sourceIp,
                traceId,
                session == null ? null : "identity-session",
                sessionPseudonym,
                aggregateVersion,
                idempotencyKey,
                Map.of("identitySessionPolicy", "ISP-1.0.0"));
    }
}
