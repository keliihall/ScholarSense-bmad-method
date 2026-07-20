package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.application.IdentitySessionRepository;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditRecord;
import cn.edu.suda.scholarsense.identityaccess.application.AuditTestSupport;
import cn.edu.suda.scholarsense.identityaccess.application.RemoteLogoutOutboxPort;
import cn.edu.suda.scholarsense.identityaccess.application.RemoteLogoutRequest;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandResult;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandService;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandType;
import cn.edu.suda.scholarsense.identityaccess.application.SessionIdempotencyRepository;
import cn.edu.suda.scholarsense.identityaccess.application.StoredSessionCommand;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

class IdentitySessionControllerReplayTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");
    private static final String KEY = "idem_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";

    @Test
    void replaysTheCommittedLogoutAfterTheHttpSessionWasInvalidated() {
        var store = new Store();
        var firstRequest = request();
        MockHttpSession httpSession = new MockHttpSession(null, "internal-cookie-bearer");
        firstRequest.setSession(httpSession);
        store.save(IdentitySession.authenticate(
                httpSession.getId(), "sp_RWxQcW41M2dSeHVIZ0JpYw", "actor-pseudo",
                "browser-pseudo", "https://app.stage.invalid", "family", "digest", NOW));
        var commands = new SessionCommandService(
                store, store, AuditTestSupport.factory(), store, store, work -> work.get(),
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));
        var controller = new IdentitySessionController(null, null, commands);

        SessionCommandResult first = controller.logout(
                new IdentitySessionController.SessionCommandRequest(1),
                KEY, firstRequest, new MockHttpServletResponse()).getBody();
        SessionCommandResult replay = controller.logout(
                new IdentitySessionController.SessionCommandRequest(1),
                KEY, request(), new MockHttpServletResponse()).getBody();

        assertEquals(first, replay);
        assertEquals(1, store.audit.size());
        assertEquals(1, store.outbox.size());
    }

    @Test
    void anUnknownDetachedKeyCannotExecuteANewLogout() {
        var store = new Store();
        var commands = new SessionCommandService(
                store, store, AuditTestSupport.factory(), store, store, work -> work.get(),
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));
        var controller = new IdentitySessionController(null, null, commands);

        IdentityAccessException error = assertThrows(IdentityAccessException.class, () ->
                controller.logout(
                        new IdentitySessionController.SessionCommandRequest(1),
                        "idem_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPR",
                        request(), new MockHttpServletResponse()));

        assertEquals("IDENTITY_SESSION_REQUIRED", error.code());
        assertEquals(1, store.audit.size());
        assertEquals(0, store.outbox.size());
        assertEquals("IDENTITY_SESSION_REQUIRED", store.audit.getFirst().fact().reasonCode());
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/v1/identity-sessions/logout");
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("Traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
        return request;
    }

    private static final class Store implements
            IdentitySessionRepository,
            SessionIdempotencyRepository,
            IdentityAuditPort,
            RemoteLogoutOutboxPort {
        private final Map<String, IdentitySession> sessions = new LinkedHashMap<>();
        private final List<StoredSessionCommand> commands = new ArrayList<>();
        private final List<IdentityAuditRecord> audit = new ArrayList<>();
        private final List<RemoteLogoutRequest> outbox = new ArrayList<>();

        @Override public Optional<IdentitySession> findById(String id) {
            return Optional.ofNullable(sessions.get(id));
        }
        @Override public void save(IdentitySession session) { sessions.put(session.sessionId(), session); }
        @Override public Optional<StoredSessionCommand> find(
                String sessionId, SessionCommandType type, String key) {
            return commands.stream().filter(value -> value.sessionId().equals(sessionId)
                    && value.commandType() == type && value.idempotencyKey().equals(key)).findFirst();
        }
        @Override public Optional<StoredSessionCommand> find(SessionCommandType type, String key) {
            return commands.stream().filter(value -> value.commandType() == type
                    && value.idempotencyKey().equals(key)).findFirst();
        }
        @Override public void save(StoredSessionCommand value) { commands.add(value); }
        @Override public void append(IdentityAuditRecord fact) { audit.add(fact); }
        @Override public void enqueue(RemoteLogoutRequest request) { outbox.add(request); }
    }
}
