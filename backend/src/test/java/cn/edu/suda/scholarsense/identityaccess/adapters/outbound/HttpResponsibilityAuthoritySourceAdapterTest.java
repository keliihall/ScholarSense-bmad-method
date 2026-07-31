package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourcePoisonException;
import cn.edu.suda.scholarsense.runtime.ResponsibilityAuthorityRuntimeProfile;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HttpResponsibilityAuthoritySourceAdapterTest {
    private static final String SIGNATURE = "responsibility-signature";
    private static final String TRACE =
            "11111111111111111111111111111111";
    private static final Instant NOW =
            Instant.parse("2026-07-30T00:00:00Z");
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "responsibility-authority",
            "sandbox-0",
            "responsibility");

    @Test
    void fetchUsesBoundedAuthenticatedResponsibilityRouteAndNormalizes()
            throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        byte[] body = fixture();
        HttpServer server = server(body, authorization, query);
        try {
            var batch = adapter(server, true).fetch(KEY, 6, TRACE);

            assertEquals("Bearer sandbox-workload", authorization.get());
            assertEquals(
                    "afterWatermark=6&consumerProjection=responsibility",
                    query.get());
            assertEquals(7, batch.toWatermark());
            assertEquals(42, batch.supportingIdentityOrgWatermarks()
                    .get("identity-authority|sandbox-0"));
            assertEquals(1, batch.relations().size());
            assertEquals(
                    "RESPONSIBILITY-STUDENT-REF",
                    batch.relations().getFirst().studentSourceReference()
                            .purposeCode());
            assertEquals(
                    "a".repeat(64),
                    batch.relations().getFirst().studentSourceReference()
                            .equivalenceDomain());
            assertEquals(
                    "e".repeat(64),
                    batch.relations().getFirst()
                            .counselorAccountRefDigest());
            assertEquals(
                    "f".repeat(64),
                    batch.relations().getFirst()
                            .collegeOrganizationRefDigest());
            assertTrue(batch.signatureVerified());
            assertFalse(batch.noChange());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidSignatureRemainsExplicitForTransactionalRejection()
            throws Exception {
        HttpServer server = server(
                fixture(), new AtomicReference<>(), new AtomicReference<>());
        try {
            assertFalse(adapter(server, false)
                    .fetch(KEY, 6, TRACE)
                    .signatureVerified());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responseLargerThanFourMiBIsPoisonedWithoutRetention()
            throws Exception {
        byte[] oversized =
                new byte[HttpResponsibilityAuthoritySourceAdapter
                        .MAX_RESPONSE_BYTES + 1];
        HttpServer server = server(
                oversized, new AtomicReference<>(), new AtomicReference<>());
        try {
            IdentitySourcePoisonException failure = assertThrows(
                    IdentitySourcePoisonException.class,
                    () -> adapter(server, true).fetch(KEY, 6, TRACE));
            assertEquals(
                    "RESPONSIBILITY_SOURCE_PAYLOAD_TOO_LARGE",
                    failure.code());
            assertEquals(
                    ResponsibilityAuthorityNormalizer.digest(new byte[0]),
                    failure.payloadDigest());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unknownEnvelopeFieldIsQuarantinedWithStableReason()
            throws Exception {
        String changed = new String(fixture(), StandardCharsets.UTF_8)
                .replaceFirst(
                        "\"records\"\\s*:",
                        "\"unknownBreakingField\":true,\"records\":");
        HttpServer server = server(
                changed.getBytes(StandardCharsets.UTF_8),
                new AtomicReference<>(),
                new AtomicReference<>());
        try {
            IdentitySourcePoisonException failure = assertThrows(
                    IdentitySourcePoisonException.class,
                    () -> adapter(server, true).fetch(KEY, 6, TRACE));
            assertEquals(
                    "RESPONSIBILITY_SOURCE_PAYLOAD_INVALID",
                    failure.code());
        } finally {
            server.stop(0);
        }
    }

    private static HttpResponsibilityAuthoritySourceAdapter adapter(
            HttpServer server, boolean signatureValid) {
        URI endpoint = URI.create(
                "http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/api/v1/incremental");
        var profile = new ResponsibilityAuthorityRuntimeProfile(
                KEY.sourceId(),
                KEY.feedId(),
                KEY.partitionId(),
                KEY.consumerProjection(),
                endpoint,
                Duration.ofSeconds(2),
                "account://test/responsibility-sync-worker",
                "secret://test/responsibility-authority-signature",
                "config://test/responsibility-authority-inbox",
                false);
        return new HttpResponsibilityAuthoritySourceAdapter(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build(),
                new ObjectMapper(),
                profile,
                ignored -> "Bearer sandbox-workload",
                (payload, signature, keyReference) ->
                        signatureValid
                                && SIGNATURE.equals(signature)
                                && profile.signatureKeyReference().equals(
                                        keyReference),
                (plaintext, purpose) -> new EncryptedSecret(
                        "ciphertext".getBytes(StandardCharsets.UTF_8),
                        "wrapped-key".getBytes(StandardCharsets.UTF_8),
                        profile.inboxEncryptionKeyReference(),
                        "k1",
                        "nonce".getBytes(StandardCharsets.UTF_8)),
                (purpose, rawValue) -> {
                    assertEquals("identity-external-ref", purpose);
                    assertTrue(rawValue.startsWith(
                            KEY.sourceId() + "\0"));
                    String digest = rawValue.endsWith("ACCOUNT-001")
                            ? "e".repeat(64)
                            : "f".repeat(64);
                    return "external_v1_k1_" + digest;
                },
                HttpResponsibilityAuthoritySourceAdapterTest::trustedNow,
                true);
    }

    private static HttpServer server(
            byte[] body,
            AtomicReference<String> authorization,
            AtomicReference<String> query)
            throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/incremental", exchange -> {
            authorization.set(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getQuery());
            exchange.getResponseHeaders().add(
                    "Content-Type", "application/json");
            exchange.getResponseHeaders().add(
                    "X-Responsibility-Authority-Signature", SIGNATURE);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static byte[] fixture() throws Exception {
        String value = Files.readString(Path.of(
                "..",
                "contracts",
                "responsibility-authority",
                "fixtures",
                "valid",
                "incremental-batch.json"));
        return value
                .replace(
                        "sha256:" + "3".repeat(64),
                        "sha256:"
                                + ResponsibilityAuthorityNormalizer.digest(
                                        SIGNATURE.getBytes(
                                                StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static TrustedTime trustedNow() {
        return new TrustedTime(
                NOW,
                new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(50),
                        "evidence://signed/clock/campus-ntp-a.json"));
    }
}
