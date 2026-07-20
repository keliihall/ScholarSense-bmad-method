package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;
import cn.edu.suda.scholarsense.identityaccess.application.AuditTestSupport;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySessionRepository;
import cn.edu.suda.scholarsense.identityaccess.application.OidcSessionEstablishmentService;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCookiePolicy;
import cn.edu.suda.scholarsense.identityaccess.application.TokenCustodyService;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class OidcLoginSuccessHandlerTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void dependencyFailureClearsEveryBrowserAuthenticationBearer() throws Exception {
        ClientRegistration registration = registration();
        var idToken = new OidcIdToken(
                "id-token", NOW.minusSeconds(1), NOW.plusSeconds(300),
                Map.of("sub", "subject-raw", "iss", "https://idp.stage.invalid"));
        var user = new DefaultOidcUser(List.of(new OidcUserAuthority(idToken)), idToken);
        var authentication = new OAuth2AuthenticationToken(
                user, user.getAuthorities(), registration.getRegistrationId());
        var authorizedClient = new OAuth2AuthorizedClient(
                registration, authentication.getName(),
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER, "access-token",
                        NOW, NOW.plusSeconds(300)),
                new OAuth2RefreshToken("refresh-token", NOW));
        var authorizedClients = new TrackingAuthorizedClients(authorizedClient);
        var sessions = failingSessionEstablishment();
        var handler = new OidcLoginSuccessHandler(
                authorizedClients, sessions, null, "https://app.stage.invalid");
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        var response = new MockHttpServletResponse();
        SecurityContextHolder.getContext().setAuthentication(authentication);

        handler.onAuthenticationSuccess(request, response, authentication);

        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("IDENTITY_DEPENDENCY_UNAVAILABLE"));
        String clearedCookie = response.getHeader("Set-Cookie");
        assertTrue(clearedCookie.startsWith("__Host-ScholarSense=; Path=/; Max-Age=0;"));
        assertTrue(clearedCookie.contains("Secure"));
        assertTrue(clearedCookie.contains("HttpOnly"));
        assertTrue(clearedCookie.contains("SameSite=Lax"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("school-idp", authorizedClients.removedRegistrationId);
        assertEquals(authentication.getName(), authorizedClients.removedPrincipalName);
        assertTrue(request.getSession(false) == null || request.getSession(false).isNew());
        assertFalse(response.getContentAsString().contains("KMS raw diagnostic"));
    }

    @Test
    void browserContinuationCleanupFailureCannotLeaveACommittedIdentitySession() throws Exception {
        ClientRegistration registration = registration();
        var idToken = new OidcIdToken(
                "id-token", NOW.minusSeconds(1), NOW.plusSeconds(300),
                Map.of("sub", "subject-raw", "iss", "https://idp.stage.invalid"));
        var user = new DefaultOidcUser(List.of(new OidcUserAuthority(idToken)), idToken);
        var authentication = new OAuth2AuthenticationToken(
                user, user.getAuthorities(), registration.getRegistrationId());
        var authorizedClient = new OAuth2AuthorizedClient(
                registration, authentication.getName(),
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER, "access-token",
                        NOW, NOW.plusSeconds(300)),
                new OAuth2RefreshToken("refresh-token", NOW));
        var sessions = mock(OidcSessionEstablishmentService.class);
        var handler = new OidcLoginSuccessHandler(
                new TrackingAuthorizedClients(authorizedClient), sessions, null,
                "https://app.stage.invalid");
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        var httpSession = new FailingContinuationSession();
        httpSession.setAttribute("identity.continuation", "ct_abcdefghijklmnopqrstuvwxyzABCDEFGH");
        request.setSession(httpSession);
        var response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        assertEquals(503, response.getStatus());
        verify(sessions, never()).establishAndConsume(any(), any(), any());
        assertTrue(response.getHeader("Set-Cookie").startsWith(
                "__Host-ScholarSense=; Path=/; Max-Age=0;"));
    }

    private static OidcSessionEstablishmentService failingSessionEstablishment() {
        IdentitySessionRepository repository = new IdentitySessionRepository() {
            @Override public Optional<IdentitySession> findById(String ignored) { return Optional.empty(); }
            @Override public void save(IdentitySession ignored) {}
        };
        return new OidcSessionEstablishmentService(
                repository,
                new TokenCustodyService((plaintext, purpose) -> {
                    throw new IllegalStateException("KMS raw diagnostic");
                }, ignored -> {}),
                AuditTestSupport.factory(),
                ignored -> {},
                Runnable::run,
                (purpose, raw) -> purpose + "-pseudonym",
                () -> "rf_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ",
                Clock.fixed(NOW, ZoneOffset.UTC));
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

    private static final class TrackingAuthorizedClients implements OAuth2AuthorizedClientService {
        private final OAuth2AuthorizedClient client;
        private String removedRegistrationId;
        private String removedPrincipalName;

        private TrackingAuthorizedClients(OAuth2AuthorizedClient client) {
            this.client = client;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                String clientRegistrationId, String principalName) {
            return (T) client;
        }

        @Override public void saveAuthorizedClient(
                OAuth2AuthorizedClient authorizedClient,
                org.springframework.security.core.Authentication principal) {}

        @Override public void removeAuthorizedClient(String registrationId, String principalName) {
            removedRegistrationId = registrationId;
            removedPrincipalName = principalName;
        }
    }

    private static final class FailingContinuationSession extends MockHttpSession {
        @Override
        public void removeAttribute(String name) {
            if ("identity.continuation".equals(name)) {
                throw new IllegalStateException("session store unavailable");
            }
            super.removeAttribute(name);
        }
    }
}
