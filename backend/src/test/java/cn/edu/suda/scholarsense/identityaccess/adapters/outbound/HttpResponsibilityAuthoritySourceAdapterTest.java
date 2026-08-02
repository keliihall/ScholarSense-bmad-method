package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourcePoisonException;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityAuthoritySourcePort;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> contract = new AtomicReference<>();
        byte[] body = fixture();
        HttpServer server = server(
                body, authorization, query, path, contract);
        try {
            var batch = adapter(server, true).fetch(KEY, 6, TRACE);

            assertEquals("Bearer sandbox-workload", authorization.get());
            assertEquals("/api/v1/incremental", path.get());
            assertEquals(
                    ResponsibilityAuthoritySourcePort.VERSION_1,
                    contract.get());
            assertEquals(
                    "afterWatermark=6&consumerProjection=responsibility"
                            + "&contractVersion=RESPONSIBILITY-AUTHORITY-1.0.0"
                            + "&maximumRecords=1000",
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
    void explicitV2FetchUsesTheV2RouteAndRequiresAV2Response()
            throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> contract = new AtomicReference<>();
        HttpServer server = server(
                fixture(ResponsibilityAuthoritySourcePort.VERSION_2),
                authorization,
                query,
                path,
                contract);
        try {
            var batch = adapter(server, true).fetch(
                    KEY,
                    ResponsibilityAuthoritySourcePort.VERSION_2,
                    6,
                    TRACE);

            assertEquals("/api/v2/incremental", path.get());
            assertEquals(
                    ResponsibilityAuthoritySourcePort.VERSION_2,
                    contract.get());
            assertEquals(
                    "afterWatermark=6&consumerProjection=responsibility"
                            + "&contractVersion=RESPONSIBILITY-AUTHORITY-2.0.0"
                            + "&maximumRecords=1000",
                    query.get());
            assertEquals(
                    ResponsibilityAuthoritySourcePort.VERSION_2,
                    batch.contractVersion());
            assertTrue(batch.relations().getFirst()
                    .hasInvalidationMetadata());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requestedAndReturnedContractVersionsMustMatch()
            throws Exception {
        HttpServer server = server(
                fixture(), new AtomicReference<>(), new AtomicReference<>());
        try {
            IdentitySourcePoisonException failure = assertThrows(
                    IdentitySourcePoisonException.class,
                    () -> adapter(server, true).fetch(
                            KEY,
                            ResponsibilityAuthoritySourcePort.VERSION_2,
                            6,
                            TRACE));

            assertEquals(
                    "RESPONSIBILITY_SOURCE_CONTRACT_VERSION_MISMATCH",
                    failure.code());
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
        return server(
                body,
                authorization,
                query,
                new AtomicReference<>(),
                new AtomicReference<>());
    }

    private static HttpServer server(
            byte[] body,
            AtomicReference<String> authorization,
            AtomicReference<String> query,
            AtomicReference<String> path,
            AtomicReference<String> contract)
            throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        com.sun.net.httpserver.HttpHandler handler = exchange -> {
            authorization.set(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getQuery());
            path.set(exchange.getRequestURI().getPath());
            contract.set(exchange.getRequestHeaders().getFirst(
                    "X-Responsibility-Authority-Contract-Version"));
            exchange.getResponseHeaders().add(
                    "Content-Type", "application/json");
            exchange.getResponseHeaders().add(
                    "X-Responsibility-Authority-Signature", SIGNATURE);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        };
        server.createContext("/api/v1/incremental", handler);
        server.createContext("/api/v2/incremental", handler);
        server.start();
        return server;
    }

    private static byte[] fixture() throws Exception {
        return fixture(ResponsibilityAuthoritySourcePort.VERSION_1);
    }

    private static byte[] fixture(String contractVersion)
            throws Exception {
        String value = Files.readString(Path.of(
                "..",
                "contracts",
                "responsibility-authority",
                "fixtures",
                "valid",
                "incremental-batch.json"));
        byte[] version1 = value
                .replace(
                        "sha256:" + "3".repeat(64),
                        "sha256:"
                                + ResponsibilityAuthorityNormalizer.digest(
                                        SIGNATURE.getBytes(
                                                StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.UTF_8);
        if (ResponsibilityAuthoritySourcePort.VERSION_1.equals(
                contractVersion)) {
            return version1;
        }
        ObjectMapper json = new ObjectMapper();
        ObjectNode envelope = (ObjectNode) json.readTree(version1);
        envelope.put("contractVersion", contractVersion);
        ObjectNode record = (ObjectNode) json.readTree(Files.readAllBytes(
                Path.of(
                        "..",
                        "contracts",
                        "responsibility-authority-v2",
                        "fixtures",
                        "valid",
                        "responsibility-relation-corrected.json")));
        record.remove("$schema");
        record.remove("contractVersion");
        ArrayNode records = (ArrayNode) envelope.required("records");
        records.removeAll();
        records.add(record);
        return json.writeValueAsBytes(envelope);
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
