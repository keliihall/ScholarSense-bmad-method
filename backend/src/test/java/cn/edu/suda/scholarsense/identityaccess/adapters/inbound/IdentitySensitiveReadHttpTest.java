package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.suda.scholarsense.identityaccess.application.CurrentSessionService;
import cn.edu.suda.scholarsense.identityaccess.application.AuditTestSupport;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySessionRepository;
import cn.edu.suda.scholarsense.identityaccess.application.SensitiveReadTransactionPort;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IdentitySensitiveReadHttpTest {

    @Test
    void auditCommitFailureReturnsOnlyStableEnvelopeAndNoSessionProjectionFields() throws Exception {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        IdentitySession session = IdentitySession.authenticate(
                "internal-session-secret", "sp_RWxQcW41M2dSeHVIZ0JpYw", "actor-pseudonym",
                "browser-binding", "https://app.test.invalid", "refresh-family", "refresh-digest", now);
        IdentitySessionRepository sessions = new IdentitySessionRepository() {
            @Override
            public Optional<IdentitySession> findById(String id) {
                return Optional.of(session);
            }

            @Override
            public void save(IdentitySession ignored) {}
        };
        SensitiveReadTransactionPort transactions = new SensitiveReadTransactionPort() {
            @Override
            public <T> T execute(Supplier<T> work) {
                return work.get();
            }
        };
        CurrentSessionService currentSessions = new CurrentSessionService(
                sessions,
                (actor, sessionId) -> true,
                AuditTestSupport.factory(),
                ignored -> {
                    throw new IdentityAccessException(
                            "IDENTITY_AUDIT_UNAVAILABLE", "security audit is unavailable");
                },
                transactions,
                Clock.fixed(now.plusSeconds(30), ZoneOffset.UTC));
        var controller = new IdentitySessionController(currentSessions, null, null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new IdentityExceptionHandler())
                .build();

        mvc.perform(get("/api/v1/identity-sessions/current")
                        .session(new MockHttpSession(null, "internal-session-secret"))
                        .header("Traceparent", "trace-sensitive-001"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_DEPENDENCY_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("sessionPseudonym"))))
                .andExpect(content().string(not(containsString("sessionVersion"))))
                .andExpect(content().string(not(containsString("expiresAt"))))
                .andExpect(content().string(not(containsString("internal-session-secret"))));
    }
}
