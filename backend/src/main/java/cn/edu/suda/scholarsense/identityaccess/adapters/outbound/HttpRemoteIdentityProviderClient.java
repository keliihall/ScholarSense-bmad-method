package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.RemoteIdentityProviderClient;
import cn.edu.suda.scholarsense.identityaccess.application.RemoteRefreshTokens;
import cn.edu.suda.scholarsense.identityaccess.application.SessionCommandType;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Provider-neutral back-channel OAuth client. Sensitive response bodies are never logged. */
public final class HttpRemoteIdentityProviderClient implements RemoteIdentityProviderClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private final HttpClient http;
    private final ObjectMapper json;
    private final ClientRegistrationRepository registrations;
    private final URI revocationEndpoint;
    private final URI endSessionEndpoint;
    private final URI postLogoutRedirectUri;
    private final Clock clock;
    private final boolean allowLoopbackHttpForTests;

    public HttpRemoteIdentityProviderClient(
            HttpClient http,
            ObjectMapper json,
            ClientRegistrationRepository registrations,
            URI revocationEndpoint,
            URI endSessionEndpoint,
            URI postLogoutRedirectUri,
            Clock clock) {
        this(
                http, json, registrations, revocationEndpoint, endSessionEndpoint,
                postLogoutRedirectUri, clock, false);
    }

    HttpRemoteIdentityProviderClient(
            HttpClient http,
            ObjectMapper json,
            ClientRegistrationRepository registrations,
            URI revocationEndpoint,
            URI endSessionEndpoint,
            URI postLogoutRedirectUri,
            Clock clock,
            boolean allowLoopbackHttpForTests) {
        this.http = java.util.Objects.requireNonNull(http);
        this.json = java.util.Objects.requireNonNull(json);
        this.registrations = java.util.Objects.requireNonNull(registrations);
        this.allowLoopbackHttpForTests = allowLoopbackHttpForTests;
        this.revocationEndpoint = safeEndpoint(revocationEndpoint, allowLoopbackHttpForTests);
        this.endSessionEndpoint = safeEndpoint(endSessionEndpoint, allowLoopbackHttpForTests);
        this.postLogoutRedirectUri = safeHttpsUri(postLogoutRedirectUri);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    @Override
    public RemoteRefreshTokens refresh(String registrationId, char[] refreshToken) {
        ClientRegistration registration = registration(registrationId);
        URI tokenEndpoint = safeEndpoint(
                URI.create(registration.getProviderDetails().getTokenUri()),
                allowLoopbackHttpForTests);
        String form = "grant_type=refresh_token&refresh_token=" + encode(refreshToken);
        JsonNode response = postJson(tokenEndpoint, registration, form);
        String access = requiredToken(response, "access_token");
        String refresh = response.has("refresh_token")
                ? requiredToken(response, "refresh_token") : new String(refreshToken);
        long expiresIn = response.path("expires_in").asLong(-1);
        if (expiresIn < 1 || expiresIn > 86_400) {
            throw unavailable();
        }
        return new RemoteRefreshTokens(
                access.toCharArray(), refresh.toCharArray(), clock.instant().plusSeconds(expiresIn));
    }

    @Override
    public void revokeAndEndSession(
            String registrationId,
            char[] refreshToken,
            SessionCommandType commandType) {
        ClientRegistration registration = registration(registrationId);
        postEmpty(
                revocationEndpoint,
                registration,
                "token=" + encode(refreshToken) + "&token_type_hint=refresh_token");
        postEmpty(
                endSessionEndpoint,
                registration,
                "client_id=" + encode(registration.getClientId())
                        + "&post_logout_redirect_uri=" + encode(postLogoutRedirectUri.toString())
                        + "&command_type=" + encode(commandType.name()));
    }

    private JsonNode postJson(URI endpoint, ClientRegistration registration, String form) {
        HttpResponse<java.io.InputStream> response = send(endpoint, registration, form);
        try (var body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw unavailable();
            }
            JsonNode value = json.readTree(body);
            if (value == null || !value.isObject()) {
                throw unavailable();
            }
            return value;
        } catch (IOException failure) {
            throw unavailable();
        }
    }

    private void postEmpty(URI endpoint, ClientRegistration registration, String form) {
        HttpResponse<java.io.InputStream> response = send(endpoint, registration, form);
        try (var body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw unavailable();
            }
            body.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException failure) {
            throw unavailable();
        }
    }

    private HttpResponse<java.io.InputStream> send(
            URI endpoint,
            ClientRegistration registration,
            String form) {
        byte[] credential = (registration.getClientId() + ":" + registration.getClientSecret())
                .getBytes(StandardCharsets.UTF_8);
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credential))
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException failure) {
            throw unavailable();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } finally {
            Arrays.fill(credential, (byte) 0);
        }
    }

    private ClientRegistration registration(String registrationId) {
        ClientRegistration value = registrations.findByRegistrationId(registrationId);
        if (value == null || value.getClientSecret() == null || value.getClientSecret().isBlank()) {
            throw unavailable();
        }
        return value;
    }

    private static String requiredToken(JsonNode value, String field) {
        JsonNode token = value.get(field);
        if (token == null || !token.isString()) {
            throw unavailable();
        }
        String plaintext = token.stringValue();
        if (plaintext.isBlank() || plaintext.length() > 16_384) {
            throw unavailable();
        }
        return plaintext;
    }

    private static String encode(char[] value) {
        if (value == null || value.length == 0 || value.length > 16_384) {
            throw unavailable();
        }
        return encode(new String(value));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static URI safeEndpoint(URI value, boolean allowLoopbackHttpForTests) {
        if (value == null || value.getUserInfo() != null || value.getFragment() != null
                || value.getQuery() != null || value.getHost() == null
                || !("https".equals(value.getScheme())
                        || allowLoopbackHttpForTests && deterministicLoopback(value))) {
            throw new IllegalArgumentException("IDENTITY_PROVIDER_ENDPOINT_INVALID");
        }
        return value;
    }

    private static URI safeHttpsUri(URI value) {
        if (value == null || !"https".equals(value.getScheme()) || value.getHost() == null
                || value.getUserInfo() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("IDENTITY_POST_LOGOUT_URI_INVALID");
        }
        return value;
    }

    private static boolean deterministicLoopback(URI value) {
        if (!"http".equals(value.getScheme())) {
            return false;
        }
        try {
            return InetAddress.getByName(value.getHost()).isLoopbackAddress();
        } catch (UnknownHostException failure) {
            return false;
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("IDENTITY_PROVIDER_UNAVAILABLE");
    }
}
