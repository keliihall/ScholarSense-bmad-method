package cn.edu.suda.scholarsense.contractfixture.publicintegration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Local-only JDK 25 HttpClient, mTLS, digest, and callback-security evidence. */
class PublicIntegrationHttpReferenceAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-03T08:00:00Z");
    private static final byte[] BODY = "{\"operation\":\"create\"}"
            .getBytes(StandardCharsets.UTF_8);
    private static PublicIntegrationMtlsTestSupport material;

    @BeforeAll
    static void createNonProductionMtlsMaterial() throws Exception {
        material = PublicIntegrationMtlsTestSupport.create();
    }

    @AfterAll
    static void destroyNonProductionMtlsMaterial() throws Exception {
        material.close();
    }

    @Test
    void outboundUsesActualMutualTlsJwtCnfAndBodyDigest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpsServer server = material.server(exchange -> {
            calls.incrementAndGet();
            HttpsExchange https = (HttpsExchange) exchange;
            assertTrue(https.getSSLSession().getPeerCertificates().length > 0);
            assertEquals("Bearer jwt.synthetic", exchange.getRequestHeaders()
                    .getFirst("Authorization"));
            assertEquals(PublicIntegrationHttpReferenceAdapter.contentDigest(BODY),
                    exchange.getRequestHeaders().getFirst("Content-Digest"));
            assertEquals("identity", exchange.getRequestHeaders()
                    .getFirst("Content-Encoding"));
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"resultCode\":\"CONFIRMED\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            var adapter = adapter(Duration.ofSeconds(2));
            var result = adapter.send(
                    endpoint(server, "/provider/tasks"), BODY,
                    "idem-01", "pe1.synthetic",
                    new PublicIntegrationHttpReferenceAdapter.WorkloadToken(
                            "jwt.synthetic", material.certificateThumbprint()));

            assertEquals(PublicIntegrationHttpReferenceAdapter.Outcome.CONFIRMED,
                    result.outcome());
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void outboundFailsClosedBeforeNetworkForUnsafeInputs() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpsServer server = material.server(exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        try {
            var adapter = adapter(Duration.ofSeconds(1));
            assertThrows(IllegalArgumentException.class, () -> adapter.send(
                    endpoint(server, "/provider/tasks"), new byte[65_537],
                    "idem-02", "pe1.synthetic",
                    new PublicIntegrationHttpReferenceAdapter.WorkloadToken(
                            "jwt.synthetic", material.certificateThumbprint())));
            assertThrows(IllegalStateException.class, () -> adapter.send(
                    endpoint(server, "/provider/tasks"), BODY,
                    "idem-02", "pe1.synthetic",
                    new PublicIntegrationHttpReferenceAdapter.WorkloadToken(
                            "jwt.synthetic", "sha256:wrong-certificate")));
            assertEquals(0, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void wrongTrustClientCertificateAndHostnameCannotReachHandler() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpsServer server = material.server(exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        try {
            var wrongTrust = new PublicIntegrationHttpReferenceAdapter(
                    HttpClient.newBuilder()
                            .sslContext(javax.net.ssl.SSLContext.getDefault())
                            .connectTimeout(Duration.ofSeconds(1))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build(),
                    Duration.ofSeconds(1), 65_536,
                    material.certificateThumbprint());
            var missingClientCertificate = new PublicIntegrationHttpReferenceAdapter(
                    HttpClient.newBuilder()
                            .sslContext(material.trustOnlySslContext())
                            .connectTimeout(Duration.ofSeconds(1))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build(),
                    Duration.ofSeconds(1), 65_536,
                    material.certificateThumbprint());
            var token = new PublicIntegrationHttpReferenceAdapter.WorkloadToken(
                    "jwt.synthetic", material.certificateThumbprint());

            assertEquals(PublicIntegrationHttpReferenceAdapter.Outcome.RETRYABLE,
                    wrongTrust.send(endpoint(server, "/wrong-ca"), BODY,
                            "idem-ca", "pe1.ca", token).outcome());
            assertEquals(PublicIntegrationHttpReferenceAdapter.Outcome.RETRYABLE,
                    missingClientCertificate.send(endpoint(server, "/missing-client-cert"),
                            BODY, "idem-cert", "pe1.cert", token).outcome());
            URI wrongHostname = URI.create("https://127.0.0.1:"
                    + server.getAddress().getPort() + "/wrong-hostname");
            assertEquals(PublicIntegrationHttpReferenceAdapter.Outcome.RETRYABLE,
                    adapter(Duration.ofSeconds(1)).send(wrongHostname, BODY,
                            "idem-host", "pe1.host", token).outcome());
            assertEquals(0, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerAcceptThenLostResponseRetriesWithoutSecondEffect() throws Exception {
        Set<String> appliedEffects = ConcurrentHashMap.newKeySet();
        AtomicInteger sideEffects = new AtomicInteger();
        HttpsServer server = material.server(exchange -> {
            exchange.getRequestBody().readAllBytes();
            String effectKey = exchange.getRequestHeaders()
                    .getFirst("X-Provider-Effect-Key");
            if (appliedEffects.add(effectKey)) {
                sideEffects.incrementAndGet();
                try {
                    Thread.sleep(650);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] response = "{\"resultCode\":\"CONFIRMED\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            var adapter = adapter(Duration.ofMillis(500));
            var token = new PublicIntegrationHttpReferenceAdapter.WorkloadToken(
                    "jwt.synthetic", material.certificateThumbprint());
            assertEquals(PublicIntegrationHttpReferenceAdapter.Outcome.RETRYABLE,
                    adapter.send(endpoint(server, "/provider/tasks"), BODY,
                            "idem-lost", "pe1.lost", token).outcome());
            assertEquals(PublicIntegrationHttpReferenceAdapter.Outcome.CONFIRMED,
                    adapter.send(endpoint(server, "/provider/tasks"), BODY,
                            "idem-lost", "pe1.lost", token).outcome());
            assertEquals(1, sideEffects.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void redirectsMixedAckTimeoutAndHttpErrorsAreClassifiedSafely() throws Exception {
        HttpsServer server = material.server(exchange -> {
            String path = exchange.getRequestURI().getPath();
            exchange.getRequestBody().readAllBytes();
            int status = switch (path) {
                case "/redirect" -> 302;
                case "/mixed" -> 207;
                case "/conflict" -> 409;
                case "/invalid" -> 422;
                case "/throttle" -> 429;
                case "/unavailable" -> 503;
                case "/unauthorized" -> 401;
                case "/forbidden" -> 403;
                default -> 200;
            };
            if ("/timeout".equals(path)) {
                try {
                    Thread.sleep(700);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (status == 302) {
                exchange.getResponseHeaders().set(
                        "Location", "https://redirect.invalid/unsafe");
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        try {
            var adapter = adapter(Duration.ofMillis(500));
            assertOutcome(adapter, server, "/redirect",
                    PublicIntegrationHttpReferenceAdapter.Outcome.UNSAFE_REDIRECT);
            assertOutcome(adapter, server, "/mixed",
                    PublicIntegrationHttpReferenceAdapter.Outcome.UNSUPPORTED_ACK);
            assertOutcome(adapter, server, "/conflict",
                    PublicIntegrationHttpReferenceAdapter.Outcome.CONFLICT);
            assertOutcome(adapter, server, "/invalid",
                    PublicIntegrationHttpReferenceAdapter.Outcome.INVALID_REQUEST);
            assertOutcome(adapter, server, "/throttle",
                    PublicIntegrationHttpReferenceAdapter.Outcome.RETRYABLE);
            assertOutcome(adapter, server, "/unavailable",
                    PublicIntegrationHttpReferenceAdapter.Outcome.RETRYABLE);
            assertOutcome(adapter, server, "/unauthorized",
                    PublicIntegrationHttpReferenceAdapter.Outcome.AUTHENTICATION_FAILED);
            assertOutcome(adapter, server, "/forbidden",
                    PublicIntegrationHttpReferenceAdapter.Outcome.AUTHORIZATION_FAILED);
            assertOutcome(adapter, server, "/timeout",
                    PublicIntegrationHttpReferenceAdapter.Outcome.RETRYABLE);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void callbackBindsMethodTargetDigestTimestampNonceKeyAndClientCertificate()
            throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var nonces = new PublicIntegrationCallbackSecurityVerifier.InMemoryNonceStore();
        var verifier = new PublicIntegrationCallbackSecurityVerifier(
                Clock.fixed(NOW, ZoneOffset.UTC), nonces,
                keyId -> "callback-key-01".equals(keyId) ? keys.getPublic() : null,
                Duration.ofSeconds(300), 65_536);
        String digest = PublicIntegrationHttpReferenceAdapter.contentDigest(BODY);
        String signatureBase = PublicIntegrationCallbackSecurityVerifier.signatureBase(
                "POST", "/callbacks/public-integration", NOW, "nonce-01",
                digest, "PIC-1.0.0");
        var signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(signatureBase.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        var request = new PublicIntegrationCallbackSecurityVerifier.CallbackRequest(
                "POST", "/callbacks/public-integration", BODY, NOW, "nonce-01",
                "ed25519", "callback-key-01", signature, digest,
                "PIC-1.0.0", material.certificateThumbprint(),
                material.certificateThumbprint());

        assertEquals(PublicIntegrationCallbackSecurityVerifier.Decision.ACCEPT,
                verifier.verify(request));
        assertEquals(PublicIntegrationCallbackSecurityVerifier.Decision.REPLAY,
                verifier.verify(request));
        assertEquals(PublicIntegrationCallbackSecurityVerifier.Decision.STALE_TIMESTAMP,
                verifier.verify(request.withTimestamp(NOW.minusSeconds(301))));
        assertEquals(PublicIntegrationCallbackSecurityVerifier.Decision.UNKNOWN_ALGORITHM,
                verifier.verify(request.withNonceAndAlgorithm("nonce-02", "rsa-sha1")));
        assertEquals(PublicIntegrationCallbackSecurityVerifier.Decision.CERTIFICATE_BINDING_MISMATCH,
                verifier.verify(request.withNonceAndJwtCnf("nonce-03", "sha256:other")));
        assertEquals(PublicIntegrationCallbackSecurityVerifier.Decision.DIGEST_MISMATCH,
                verifier.verify(request.withNonceAndBody("nonce-04", "tamper".getBytes(
                        StandardCharsets.UTF_8))));
    }

    private static PublicIntegrationHttpReferenceAdapter adapter(Duration timeout) {
        return new PublicIntegrationHttpReferenceAdapter(
                HttpClient.newBuilder()
                        .sslContext(material.sslContext())
                        .connectTimeout(Duration.ofSeconds(1))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                timeout,
                65_536,
                material.certificateThumbprint());
    }

    private static void assertOutcome(
            PublicIntegrationHttpReferenceAdapter adapter,
            HttpsServer server,
            String path,
            PublicIntegrationHttpReferenceAdapter.Outcome expected) {
        var result = adapter.send(
                endpoint(server, path), BODY, "idem-classify", "pe1.classify",
                new PublicIntegrationHttpReferenceAdapter.WorkloadToken(
                        "jwt.synthetic", material.certificateThumbprint()));
        assertEquals(expected, result.outcome());
    }

    private static URI endpoint(HttpsServer server, String path) {
        return URI.create("https://localhost:" + server.getAddress().getPort() + path);
    }
}
