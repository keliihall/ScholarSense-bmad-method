package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import cn.edu.suda.scholarsense.identityaccess.application.ContinuationService;
import cn.edu.suda.scholarsense.identityaccess.application.CurrentSessionService;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySessionRepository;
import cn.edu.suda.scholarsense.identityaccess.application.OidcSessionEstablishmentService;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditFactFactory;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditPort;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandResult;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandService;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class IdentityReplaySecurityFilterChainTest {
    private static final String KEY = "idem_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";
    private static final String UNKNOWN_KEY = "idem_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPR";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "test", java.util.Map.of(
                        "scholarsense.identity.enabled", "true",
                        "scholarsense.identity.application-origin", "https://app.stage.invalid")));
        context.register(TestConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void detachedReplayPassesAuthenticationAndCsrfFiltersWithTheOriginalProof() throws Exception {
        SessionCommandService commands = context.getBean(SessionCommandService.class);
        var result = new SessionCommandResult(
                "sp_RWxQcW41M2dSeHVIZ0JpYw", SessionCommandType.LOGOUT, 2,
                Instant.parse("2026-07-20T00:00:00Z"), "/scholarsense/", true);
        when(commands.replayCompleted(
                eq(SessionCommandType.LOGOUT), eq(KEY),
                anyString(), anyString(), anyString())).thenReturn(Optional.of(result));

        var csrfResponse = mvc.perform(get("/api/v1/identity-sessions/csrf").secure(true))
                .andReturn().getResponse();
        assertEquals(200, csrfResponse.getStatus());
        Cookie csrfCookie = csrfResponse.getCookie("__Host-ScholarSense-CSRF");
        assertNotNull(csrfCookie);
        String body = csrfResponse.getContentAsString();
        var matcher = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"").matcher(body);
        assertTrue(matcher.find());
        String csrfToken = matcher.group(1);

        var replayResponse = mvc.perform(post("/api/v1/identity-sessions/logout")
                        .secure(true)
                        .cookie(csrfCookie)
                        .header("Origin", "https://app.stage.invalid")
                        .header("X-CSRF-TOKEN", csrfToken)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionVersion\":1}"))
                .andReturn().getResponse();

        assertEquals(200, replayResponse.getStatus());
        assertTrue(replayResponse.getContentAsString().contains("sp_RWxQcW41M2dSeHVIZ0JpYw"));
        verify(commands).replayCompleted(
                eq(SessionCommandType.LOGOUT), eq(KEY),
                anyString(), anyString(), anyString());
    }

    @Test
    void missingCsrfProofIsRejectedWithTheFrozenEnvelopeBeforeControllerExecution() throws Exception {
        var response = mvc.perform(post("/api/v1/identity-sessions/logout")
                        .secure(true)
                        .header("Origin", "https://app.stage.invalid")
                        .header("Idempotency-Key", UNKNOWN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionVersion\":1}"))
                .andReturn().getResponse();

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains(
                "\"code\":\"IDENTITY_SESSION_REQUIRED\""));
        verify(context.getBean(SessionCommandService.class)).auditAnonymousRejection(
                eq(SessionCommandType.LOGOUT), eq(UNKNOWN_KEY), anyString(), anyString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({IdentitySecurityConfiguration.class, IdentityExceptionHandler.class})
    static class TestConfiguration {
        @Bean CurrentSessionService currentSessionService() { return mock(CurrentSessionService.class); }
        @Bean ContinuationService continuationService() { return mock(ContinuationService.class); }
        @Bean SessionCommandService sessionCommandService() { return mock(SessionCommandService.class); }
        @Bean OidcSessionEstablishmentService establishmentService() {
            return mock(OidcSessionEstablishmentService.class);
        }
        @Bean IdentitySessionRepository identitySessionRepository() {
            return mock(IdentitySessionRepository.class);
        }
        @Bean IdentityAuditFactFactory identityAuditFactFactory() {
            return mock(IdentityAuditFactFactory.class);
        }
        @Bean IdentityAuditPort identityAuditPort() { return mock(IdentityAuditPort.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean IdentitySessionController identitySessionController(
                CurrentSessionService current,
                ContinuationService continuations,
                SessionCommandService commands) {
            return new IdentitySessionController(current, continuations, commands);
        }
        @Bean ClientRegistrationRepository clientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(registration());
        }
        @Bean OAuth2AuthorizedClientService authorizedClientService(
                ClientRegistrationRepository registrations) {
            return new InMemoryOAuth2AuthorizedClientService(registrations);
        }

        private static ClientRegistration registration() {
            return ClientRegistration.withRegistrationId("school-idp")
                    .clientId("client-id")
                    .clientSecret("client-secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("https://app.stage.invalid/login/oauth2/code/school-idp")
                    .scope("openid")
                    .authorizationUri("https://idp.stage.invalid/authorize")
                    .tokenUri("https://idp.stage.invalid/token")
                    .jwkSetUri("https://idp.stage.invalid/jwks")
                    .userNameAttributeName("sub")
                    .clientName("School IdP")
                    .build();
        }
    }
}
