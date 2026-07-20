package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.application.HostInputRejectionAuditService;
import cn.edu.suda.scholarsense.identityaccess.application.AuditTestSupport;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditRecord;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySessionRepository;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

class HostInputRejectionControllerTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void acceptsOnlyCodeAndUsesTheCurrentSessionContext() {
        var identity = IdentitySession.authenticate(
                "internal-cookie-bearer", "sp_RWxQcW41M2dSeHVIZ0JpYw", "actor-pseudo",
                "browser-pseudo", "https://app.stage.invalid", "family", "digest", NOW);
        var facts = new ArrayList<IdentityAuditRecord>();
        var controller = new HostInputRejectionController(new HostInputRejectionAuditService(
                repository(identity), AuditTestSupport.factory(), facts::add));
        var request = request(identity.sessionId());

        assertEquals("recorded", controller.reject(
                Map.of("code", "HOST_SOURCE_FORBIDDEN"), request).get("status"));
        assertEquals("HOST_SOURCE_FORBIDDEN", facts.getFirst().fact().reasonCode());

        var rawPayload = new LinkedHashMap<String, Object>();
        rawPayload.put("code", "HOST_MESSAGE_INVALID");
        rawPayload.put("payload", Map.of("secret", "forbidden"));
        IdentityAccessException error = assertThrows(
                IdentityAccessException.class, () -> controller.reject(rawPayload, request));
        assertEquals("HOST_MESSAGE_INVALID", error.code());
        assertEquals(1, facts.size());
    }

    private static MockHttpServletRequest request(String sessionId) {
        var request = new MockHttpServletRequest("POST", "/api/v1/host-input-rejections");
        request.setSession(new MockHttpSession(null, sessionId));
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("Traceparent", "trace-host-001");
        return request;
    }

    private static IdentitySessionRepository repository(IdentitySession session) {
        return new IdentitySessionRepository() {
            @Override public Optional<IdentitySession> findById(String id) {
                return session.sessionId().equals(id) ? Optional.of(session) : Optional.empty();
            }
            @Override public void save(IdentitySession ignored) {}
        };
    }
}
