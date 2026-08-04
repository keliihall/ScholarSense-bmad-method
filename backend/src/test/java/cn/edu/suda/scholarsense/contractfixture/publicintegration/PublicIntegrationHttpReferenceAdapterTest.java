package cn.edu.suda.scholarsense.contractfixture.publicintegration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
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
            assertEquals("PIC-1.0.0", exchange.getRequestHeaders()
                    .getFirst("X-ScholarSense-Contract-Version"));
            assertEquals(null, exchange.getRequestHeaders()
                    .getFirst("X-PIC-Contract-Version"));
            exchange.getRequestBody().readAllBytes();
            byte[] response = receipt("idem-01", "pe1.synthetic");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, response.length);
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
        CountDownLatch duplicateRequestObserved = new CountDownLatch(1);
        HttpsServer server = material.server(exchange -> {
            exchange.getRequestBody().readAllBytes();
            String effectKey = exchange.getRequestHeaders()
                    .getFirst("X-Provider-Effect-Key");
            if (appliedEffects.add(effectKey)) {
                sideEffects.incrementAndGet();
                try {
                    if (!duplicateRequestObserved.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "PIC_TEST_DUPLICATE_REQUEST_NOT_OBSERVED");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            } else {
                duplicateRequestObserved.countDown();
            }
            byte[] response = receipt("idem-lost", "pe1.lost");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, response.length);
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
    void arbitrary2xxOrMalformedReceiptNeverConfirmsAndResponseReadIsBounded()
            throws Exception {
        HttpsServer server = material.server(exchange -> {
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            byte[] response = switch (path) {
                case "/ok-200" -> receipt("idem-receipt", "pe1.receipt");
                case "/valid" -> receipt("idem-receipt", "pe1.receipt");
                case "/wrong-scope" -> receipt("another-scope", "pe1.receipt");
                case "/malformed" -> "{\"resultCode\":\"ACCEPTED\"}"
                        .getBytes(StandardCharsets.UTF_8);
                default -> new byte[65_537];
            };
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(
                    "/ok-200".equals(path) ? 200 : 202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            var adapter = adapter(Duration.ofSeconds(2));
            var token = new PublicIntegrationHttpReferenceAdapter.WorkloadToken(
                    "jwt.synthetic", material.certificateThumbprint());
            assertEquals(PublicIntegrationHttpReferenceAdapter.Outcome.CONFIRMED,
                    adapter.send(endpoint(server, "/valid"), BODY,
                            "idem-receipt", "pe1.receipt", token).outcome());
            for (String path : new String[]{
                    "/ok-200", "/wrong-scope", "/malformed", "/oversized"}) {
                assertEquals(PublicIntegrationHttpReferenceAdapter.Outcome.UNSAFE_RESPONSE,
                        adapter.send(endpoint(server, path), BODY,
                                "idem-receipt", "pe1.receipt", token).outcome());
            }
            String source = Files.readString(Path.of(
                    "src/test/java/cn/edu/suda/scholarsense/contractfixture/"
                            + "publicintegration/PublicIntegrationHttpReferenceAdapter.java"));
            assertTrue(source.contains("BodyHandlers.ofInputStream"));
            assertTrue(!source.contains("BodyHandlers.ofByteArray"));
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
        var replay = new PublicIntegrationCallbackSecurityVerifier.InMemoryReplayStore();
        var verifier = new PublicIntegrationCallbackSecurityVerifier(
                Clock.fixed(NOW, ZoneOffset.UTC), replay,
                keyId -> "callback-key-01".equals(keyId) ? keys.getPublic() : null,
                keyId -> "workload-key-01".equals(keyId) ? keys.getPublic() : null,
                "issuer.synthetic", "pic-callback", "pic.callback",
                Duration.ofSeconds(300), 65_536);
        String digest = PublicIntegrationHttpReferenceAdapter.contentDigest(BODY);
        String workloadToken = workloadToken(keys, material.certificateThumbprint());
        String signatureInput = PublicIntegrationCallbackSecurityVerifier.signatureInput(
                NOW, "nonce-01", "callback-key-01");
        AtomicReference<SSLSession> inboundTls = new AtomicReference<>();
        AtomicReference<PublicIntegrationCallbackSecurityVerifier.Decision> decision =
                new AtomicReference<>();
        AtomicReference<PublicIntegrationCallbackSecurityVerifier.CallbackRequest> captured =
                new AtomicReference<>();
        HttpsServer server = material.server(exchange -> {
            HttpsExchange https = (HttpsExchange) exchange;
            byte[] callbackBody = exchange.getRequestBody().readAllBytes();
            var headers = exchange.getRequestHeaders();
            var request = new PublicIntegrationCallbackSecurityVerifier.CallbackRequest(
                    exchange.getRequestMethod(), headers.getFirst("Host"),
                    exchange.getRequestURI().getRawPath(), "", callbackBody,
                    headers.getFirst("Content-Type"),
                    Instant.parse(headers.getFirst("X-ScholarSense-Callback-Timestamp")),
                    headers.getFirst("X-ScholarSense-Callback-Nonce"),
                    headers.getFirst("Signature-Input"), headers.getFirst("Signature"),
                    headers.getFirst("Content-Digest"),
                    headers.getFirst("X-ScholarSense-Contract-Version"),
                    headers.getFirst("Authorization").substring("Bearer ".length()),
                    "urn:synthetic:provider", "event-01",
                    sha256Hex(callbackBody),
                    "accepted".getBytes(StandardCharsets.UTF_8));
            inboundTls.set(https.getSSLSession());
            captured.set(request);
            decision.set(verifier.verify(request, inboundTls.get()));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        try {
            String authority = "localhost:" + server.getAddress().getPort();
            var unsigned = new PublicIntegrationCallbackSecurityVerifier.CallbackRequest(
                    "POST", authority, "/callbacks/public-integration", "", BODY,
                    "application/cloudevents+json", NOW, "nonce-01", signatureInput,
                    "", digest, "PIC-1.0.0", workloadToken,
                    "urn:synthetic:provider", "event-01",
                    sha256Hex(BODY),
                    "accepted".getBytes(StandardCharsets.UTF_8));
            var signer = java.security.Signature.getInstance("Ed25519");
            signer.initSign(keys.getPrivate());
            signer.update(PublicIntegrationCallbackSecurityVerifier.signatureBase(unsigned)
                    .getBytes(StandardCharsets.UTF_8));
            String signature = "sig1=:" + Base64.getEncoder()
                    .encodeToString(signer.sign()) + ":";
            URI callback = endpoint(server, "/callbacks/public-integration");
            HttpRequest request = HttpRequest.newBuilder(callback)
                    .header("Content-Type", "application/cloudevents+json")
                    .header("Content-Digest", digest)
                    .header("X-ScholarSense-Contract-Version", "PIC-1.0.0")
                    .header("X-ScholarSense-Callback-Timestamp", NOW.toString())
                    .header("X-ScholarSense-Callback-Nonce", "nonce-01")
                    .header("Signature-Input", signatureInput)
                    .header("Signature", signature)
                    .header("Authorization", "Bearer " + workloadToken)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(BODY))
                    .build();
            HttpResponse<Void> response = HttpClient.newBuilder()
                    .sslContext(material.sslContext())
                    .build()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            assertEquals(204, response.statusCode());
            assertEquals(PublicIntegrationCallbackSecurityVerifier.Decision.ACCEPT,
                    decision.get());

            var verified = captured.get();
            assertEquals(PublicIntegrationCallbackSecurityVerifier.Decision.REPLAY,
                    verifier.verify(verified, inboundTls.get()));
            assertEquals(PublicIntegrationCallbackSecurityVerifier.Decision.STALE_TIMESTAMP,
                    verifier.verify(verified.withTimestamp(NOW.minusSeconds(301)),
                            inboundTls.get()));
            assertEquals(PublicIntegrationCallbackSecurityVerifier.Decision.UNKNOWN_ALGORITHM,
                    verifier.verify(verified.withSignatureInput(
                            PublicIntegrationCallbackSecurityVerifier.signatureInput(
                                    NOW, "nonce-02", "callback-key-01")
                                    .replace("alg=\"ed25519\"", "alg=\"rsa-sha1\"")),
                            inboundTls.get()));
            assertEquals(
                    PublicIntegrationCallbackSecurityVerifier.Decision.CERTIFICATE_BINDING_MISMATCH,
                    verifier.verify(verified, null));
            assertEquals(PublicIntegrationCallbackSecurityVerifier.Decision.DIGEST_MISMATCH,
                    verifier.verify(verified.withBody(
                            "tamper".getBytes(StandardCharsets.UTF_8)), inboundTls.get()));
            assertEquals(
                    PublicIntegrationCallbackSecurityVerifier.Decision.CONTRACT_VERSION_MISMATCH,
                    verifier.verify(verified.withContractVersion("PIC-1.0.1"), inboundTls.get()));
            String futureToken = workloadToken(
                    keys, material.certificateThumbprint(), NOW.plusSeconds(3_600),
                    NOW, NOW.plusSeconds(3_900));
            assertEquals(PublicIntegrationCallbackSecurityVerifier.Decision.IDENTITY_INVALID,
                    verifier.verify(verified.withWorkloadToken(futureToken), inboundTls.get()));
        } finally {
            server.stop(0);
        }
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

    private static byte[] receipt(String idempotencyScopeToken, String providerEffectKey) {
        return ("{\"acceptedAt\":\"2026-08-03T08:00:00Z\","
                + "\"idempotencyScopeToken\":\"" + idempotencyScopeToken + "\","
                + "\"operationReceiptId\":\"019fc688-380d-7391-b3d0-877e0f9b3026\","
                + "\"providerEffectKeyToken\":\"" + providerEffectKey + "\","
                + "\"resultCode\":\"ACCEPTED\","
                + "\"traceId\":\"11111111111111111111111111111111\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256Hex(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String workloadToken(
            java.security.KeyPair keys,
            String certificateThumbprint) throws Exception {
        return workloadToken(
                keys, certificateThumbprint, NOW, NOW, NOW.plusSeconds(300));
    }

    private static String workloadToken(
            java.security.KeyPair keys,
            String certificateThumbprint,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) throws Exception {
        String header = "{\"alg\":\"EdDSA\",\"kid\":\"workload-key-01\","
                + "\"typ\":\"at+jwt\"}";
        String payload = "{\"aud\":\"pic-callback\",\"cnf\":{\"x5t#S256\":\""
                + certificateThumbprint + "\"},\"exp\":" + expiresAt.getEpochSecond() + ","
                + "\"iat\":" + issuedAt.getEpochSecond() + ",\"iss\":\"issuer.synthetic\","
                + "\"jti\":\"jwt-01\",\"nbf\":" + notBefore.getEpochSecond() + ","
                + "\"scope\":\"pic.callback\",\"sub\":\"provider.synthetic\"}";
        String signingInput = Base64.getUrlEncoder().withoutPadding().encodeToString(
                header.getBytes(StandardCharsets.UTF_8)) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.getBytes(StandardCharsets.UTF_8));
        var signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signer.sign());
    }
}
