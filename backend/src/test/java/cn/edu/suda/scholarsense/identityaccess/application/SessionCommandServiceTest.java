package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import cn.edu.suda.scholarsense.identityaccess.domain.SessionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");
    private static final String LOGOUT_KEY = "idem_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";
    private static final String OTHER_KEY = "idem_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPR";
    private static final String SWITCH_KEY = "idem_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPS";
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private final FakeSessionRepository sessions = new FakeSessionRepository();
    private final FakeIdempotencyRepository idempotency = new FakeIdempotencyRepository();
    private final FakeAuditPort audit = new FakeAuditPort();
    private final FakeRemoteLogoutOutbox outbox = new FakeRemoteLogoutOutbox();
    private SessionCommandService service;

    @BeforeEach
    void setUp() {
        sessions.save(IdentitySession.authenticate(
                "session-1", "sp_RWxQcW41M2dSeHVIZ0JpYw", "actor-pseudo", "browser-hash",
                "https://app.stage.invalid",
                "family-1", "refresh-digest-1", NOW));
        service = new SessionCommandService(
                sessions, idempotency, AuditTestSupport.factory(), audit, outbox, work -> work.get(),
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));
    }

    @Test
    void logoutIsIdempotentAndDoesNotRepeatAuditOrRemoteRevocation() {
        SessionCommand command = new SessionCommand(
                SessionCommandType.LOGOUT, "session-1", 1, LOGOUT_KEY,
                "request-digest-a", "ip-pseudo", TRACE);

        SessionCommandResult first = service.execute(command);
        SessionCommandResult replay = service.execute(command);

        assertEquals(first, replay);
        assertEquals(1, audit.facts.size());
        assertEquals(1, outbox.requests.size());
        assertEquals(SessionStatus.REVOKED, sessions.findById("session-1").orElseThrow().status());
    }

    @Test
    void sameIdempotencyKeyWithDifferentDigestReturnsStableConflict() {
        service.execute(new SessionCommand(
                SessionCommandType.LOGOUT, "session-1", 1, LOGOUT_KEY,
                "request-digest-a", "ip-pseudo", TRACE));

        IdentityAccessException error = assertThrows(IdentityAccessException.class, () -> service.execute(
                new SessionCommand(SessionCommandType.LOGOUT, "session-1", 1, LOGOUT_KEY,
                        "request-digest-b", "ip-pseudo", TRACE)));
        assertEquals("IDENTITY_IDEMPOTENCY_MISMATCH", error.code());
        assertEquals("rejected", audit.facts.getLast().fact().outcome());
        assertEquals("IDENTITY_IDEMPOTENCY_MISMATCH", audit.facts.getLast().fact().reasonCode());
        assertEquals(ContinuationService.digest(LOGOUT_KEY),
                audit.facts.getLast().fact().idempotencyKeyDigest());
        assertFalse(audit.facts.getLast().toString().contains("sp_RWxQcW41M2dSeHVIZ0JpYw"));
    }

    @Test
    void detachedReplayReturnsOnlyAnAlreadyCommittedMatchingResult() {
        SessionCommandResult first = service.execute(new SessionCommand(
                SessionCommandType.LOGOUT, "session-1", 1, LOGOUT_KEY,
                "request-digest-a", "ip-pseudo", TRACE));

        assertEquals(first, service.replayCompleted(
                SessionCommandType.LOGOUT, LOGOUT_KEY, "request-digest-a",
                "ip-pseudo", TRACE).orElseThrow());
        assertEquals(Optional.empty(), service.replayCompleted(
                SessionCommandType.LOGOUT, OTHER_KEY, "request-digest-a",
                "ip-pseudo", TRACE));
        assertEquals(2, audit.facts.size());
        assertEquals("IDENTITY_SESSION_REQUIRED", audit.facts.getLast().fact().reasonCode());
        assertEquals(cn.edu.suda.scholarsense.shared.outbox.ActorType.ANONYMOUS,
                audit.facts.getLast().fact().actorType());
        assertEquals(1, outbox.requests.size());
    }

    @Test
    void detachedReplayRejectsKeysThatAreTooWeakToServeAsReplayProof() {
        assertEquals(Optional.empty(), service.replayCompleted(
                SessionCommandType.LOGOUT, "short", "request-digest-a",
                "ip-pseudo", TRACE));
        assertEquals(1, audit.facts.size());
        assertEquals("IDENTITY_SESSION_REQUIRED", audit.facts.getFirst().fact().reasonCode());
        assertEquals(cn.edu.suda.scholarsense.shared.outbox.ActorType.ANONYMOUS,
                audit.facts.getFirst().fact().actorType());
        assertEquals(ContinuationService.digest("short"),
                audit.facts.getFirst().fact().idempotencyKeyDigest());
        assertThrows(IllegalArgumentException.class, () -> new SessionCommand(
                SessionCommandType.LOGOUT, "session-1", 1, "contains space",
                "request-digest-a", "ip-pseudo", TRACE));
    }

    @Test
    void accountSwitchStaysPendingWithoutAnAuthorizationActionUntilRemoteConfirmation() {
        SessionCommandResult result = service.execute(new SessionCommand(
                SessionCommandType.ACCOUNT_SWITCH, "session-1", 1, SWITCH_KEY,
                "request-digest-a", "ip-pseudo", TRACE));

        assertEquals("/scholarsense/", result.nextAction());
        assertEquals(true, result.remoteRevocationPending());
        assertEquals("school-idp", outbox.requests.getFirst().registrationId());

        idempotency.confirmAccountSwitch(SWITCH_KEY, "school-idp");
        SessionCommandResult confirmed = service.replayCompleted(
                SessionCommandType.ACCOUNT_SWITCH, SWITCH_KEY, "request-digest-a",
                "ip-pseudo", TRACE).orElseThrow();
        assertEquals("/oauth2/authorization/school-idp", confirmed.nextAction());
        assertFalse(confirmed.remoteRevocationPending());
    }

    @Test
    void staleVersionWritesARejectionAuditOutsideTheFailedCommand() {
        IdentityAccessException error = assertThrows(IdentityAccessException.class, () ->
                service.execute(new SessionCommand(
                        SessionCommandType.LOGOUT, "session-1", 2, OTHER_KEY,
                        "request-digest-a", "ip-pseudo", TRACE)));

        assertEquals("IDENTITY_SESSION_VERSION_CONFLICT", error.code());
        assertEquals("rejected", audit.facts.getLast().fact().outcome());
        assertEquals("IDENTITY_SESSION_VERSION_CONFLICT", audit.facts.getLast().fact().reasonCode());
        assertEquals(ContinuationService.digest(OTHER_KEY),
                audit.facts.getLast().fact().idempotencyKeyDigest());
        assertEquals(SessionStatus.ACTIVE, sessions.findById("session-1").orElseThrow().status());
    }

    @Test
    void auditFailurePreventsFalseSuccessAndLocalRevocation() {
        audit.fail = true;

        assertThrows(IdentityAccessException.class, () -> service.execute(new SessionCommand(
                SessionCommandType.ACCOUNT_SWITCH, "session-1", 1, SWITCH_KEY,
                "request-digest-a", "ip-pseudo", TRACE)));

        assertEquals(SessionStatus.ACTIVE, sessions.findById("session-1").orElseThrow().status());
        assertFalse(idempotency.find("session-1", SessionCommandType.ACCOUNT_SWITCH, SWITCH_KEY).isPresent());
        assertEquals(0, outbox.requests.size());
    }

    @Test
    void concurrentSameKeyAndRequestLoserReplaysTheCommittedWinnerWithoutFalseRejection() {
        SessionCommandResult winner = new SessionCommandResult(
                "sp_RWxQcW41M2dSeHVIZ0JpYw", SessionCommandType.LOGOUT, 2,
                NOW.plusSeconds(30), "/scholarsense/", true);
        SessionTransactionPort losesAfterWinnerCommits = work -> {
            idempotency.save(new StoredSessionCommand(
                    "session-1", SessionCommandType.LOGOUT, LOGOUT_KEY,
                    "request-digest-a", winner));
            throw new IdentityAccessException(
                    "IDENTITY_SESSION_VERSION_CONFLICT", "session changed; refresh before retrying");
        };
        var concurrent = new SessionCommandService(
                sessions, idempotency, AuditTestSupport.factory(), audit, outbox,
                losesAfterWinnerCommits, Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));

        SessionCommandResult replay = concurrent.execute(new SessionCommand(
                SessionCommandType.LOGOUT, "session-1", 1, LOGOUT_KEY,
                "request-digest-a", "ip-pseudo", TRACE));

        assertEquals(winner, replay);
        assertEquals(0, audit.facts.size());
    }

    private static final class FakeSessionRepository implements IdentitySessionRepository {
        private final Map<String, IdentitySession> values = new HashMap<>();

        @Override
        public Optional<IdentitySession> findById(String sessionId) {
            return Optional.ofNullable(values.get(sessionId));
        }

        @Override
        public void save(IdentitySession session) {
            values.put(session.sessionId(), session);
        }
    }

    private static final class FakeIdempotencyRepository implements SessionIdempotencyRepository {
        private final Map<String, StoredSessionCommand> values = new HashMap<>();

        @Override
        public Optional<StoredSessionCommand> find(String sessionId, SessionCommandType type, String key) {
            return Optional.ofNullable(values.get(sessionId + type + key));
        }

        @Override
        public Optional<StoredSessionCommand> find(SessionCommandType type, String key) {
            return values.values().stream()
                    .filter(value -> value.commandType() == type && value.idempotencyKey().equals(key))
                    .findFirst();
        }

        @Override
        public void save(StoredSessionCommand value) {
            values.put(value.sessionId() + value.commandType() + value.idempotencyKey(), value);
        }

        private void confirmAccountSwitch(String key, String registrationId) {
            StoredSessionCommand stored = find(SessionCommandType.ACCOUNT_SWITCH, key).orElseThrow();
            SessionCommandResult current = stored.result();
            save(new StoredSessionCommand(
                    stored.sessionId(), stored.commandType(), stored.idempotencyKey(), stored.requestDigest(),
                    new SessionCommandResult(
                            current.sessionPseudonym(), current.commandType(), current.sessionVersion(),
                            current.completedAt(), "/oauth2/authorization/" + registrationId, false)));
        }
    }

    private static final class FakeAuditPort implements IdentityAuditPort {
        private final List<IdentityAuditRecord> facts = new ArrayList<>();
        private boolean fail;

        @Override
        public void append(IdentityAuditRecord fact) {
            if (fail) {
                throw new IdentityAccessException("IDENTITY_AUDIT_UNAVAILABLE", "security audit unavailable");
            }
            facts.add(fact);
        }
    }

    private static final class FakeRemoteLogoutOutbox implements RemoteLogoutOutboxPort {
        private final List<RemoteLogoutRequest> requests = new ArrayList<>();

        @Override
        public void enqueue(RemoteLogoutRequest request) {
            requests.add(request);
        }
    }
}
