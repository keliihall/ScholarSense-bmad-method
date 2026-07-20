package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.application.RemoteRefreshTokens;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import tools.jackson.databind.ObjectMapper;

class HttpRemoteIdentityProviderClientTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void performsRefreshRevocationAndEndSessionAgainstADeterministicMockIdp() throws Exception {
        var tokenRequest = new AtomicReference<String>();
        var revocationRequest = new AtomicReference<String>();
        var endSessionRequest = new AtomicReference<String>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/token", exchange -> respond(
                exchange, tokenRequest,
                "{\"access_token\":\"access-next\",\"refresh_token\":\"refresh-next\","
                        + "\"token_type\":\"Bearer\",\"expires_in\":300}"));
        server.createContext("/revoke", exchange -> respond(exchange, revocationRequest, "{}"));
        server.createContext("/logout", exchange -> respond(exchange, endSessionRequest, "{}"));
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            ClientRegistration registration = registration(base);
            var client = new HttpRemoteIdentityProviderClient(
                    HttpClient.newHttpClient(),
                    new ObjectMapper(),
                    new InMemoryClientRegistrationRepository(registration),
                    base.resolve("/revoke"),
                    base.resolve("/logout"),
                    URI.create("https://app.stage.invalid/scholarsense/"),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    true);

            try (RemoteRefreshTokens refreshed = client.refresh(
                    "school-idp", "refresh-original".toCharArray())) {
                assertEquals(NOW.plusSeconds(300), refreshed.accessExpiresAt());
            }
            client.revokeAndEndSession(
                    "school-idp", "refresh-next".toCharArray(), SessionCommandType.ACCOUNT_SWITCH);

            assertTrue(tokenRequest.get().contains("grant_type=refresh_token"));
            assertTrue(tokenRequest.get().contains("refresh_token=refresh-original"));
            assertTrue(revocationRequest.get().contains("token=refresh-next"));
            assertTrue(endSessionRequest.get().contains("post_logout_redirect_uri="));
        } finally {
            server.stop(0);
        }
    }

    private static ClientRegistration registration(URI base) {
        return ClientRegistration.withRegistrationId("school-idp")
                .clientId("client-id")
                .clientSecret("client-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://app.stage.invalid/login/oauth2/code/school-idp")
                .scope("openid")
                .authorizationUri(base.resolve("/authorize").toString())
                .tokenUri(base.resolve("/token").toString())
                .jwkSetUri(base.resolve("/jwks").toString())
                .userNameAttributeName("sub")
                .clientName("Mock IdP")
                .build();
    }

    private static void respond(
            HttpExchange exchange,
            AtomicReference<String> body,
            String response) throws IOException {
        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        assertTrue(exchange.getRequestHeaders().getFirst("Authorization").startsWith("Basic "));
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
