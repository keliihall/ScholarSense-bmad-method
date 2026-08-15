package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.identityaccess.application.RemoteRefreshTokens;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandType;
import cn.edu.suda.scholarsense.runtime.RuntimeEnvironment;
import cn.edu.suda.scholarsense.shared.observability.ObservationPort;
import cn.edu.suda.scholarsense.shared.observability.TrustedHttpClient;
import cn.edu.suda.scholarsense.shared.observability.TrustedHttpClientFactory;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContext;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContextCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.io.ByteArrayInputStream;
import java.util.Map;
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

    @Test
    void basicAuthBackChannelPreservesCredentialsAndAddsOnlyGovernedTraceHeaders()
            throws Exception {
        HttpClient raw = mock(HttpClient.class);
        when(raw.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(
                "{\"access_token\":\"next\",\"expires_in\":300}"
                        .getBytes(StandardCharsets.UTF_8)));
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        when(raw.send(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return response;
        });
        W3cTraceContext parent = new W3cTraceContext(
                "00112233445566778899aabbccddeeff", "1111111111111111", true);
        W3cTraceContext child = new W3cTraceContext(
                parent.traceId(), "2222222222222222", true);
        ObservationPort observations = mock(ObservationPort.class);
        ObservationPort.ObservationScope scope = mock(ObservationPort.ObservationScope.class);
        when(observations.start(any(), any(), any(), any())).thenReturn(scope);
        when(scope.context()).thenReturn(child);
        URI base = URI.create("https://localhost:43191");
        TrustedHttpClient governed = new TrustedHttpClientFactory(
                () -> java.util.Optional.of(parent), new W3cTraceContextCodec(),
                RuntimeEnvironment.TEST, observations).wrapSandboxIdentityProvider(
                        raw, base.resolve("/revoke"), base.resolve("/logout"));
        var client = new HttpRemoteIdentityProviderClient(
                governed, new ObjectMapper(),
                new InMemoryClientRegistrationRepository(registration(base)),
                base.resolve("/revoke"), base.resolve("/logout"),
                URI.create("https://app.stage.invalid/scholarsense/"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        try (RemoteRefreshTokens ignored = client.refresh(
                "school-idp", "refresh-original".toCharArray())) {
            assertTrue(sent.get().headers().firstValue("Authorization")
                    .orElseThrow().startsWith("Basic "));
            assertEquals("00-" + child.traceId() + "-" + child.spanId() + "-01",
                    sent.get().headers().firstValue("traceparent").orElseThrow());
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
