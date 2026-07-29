package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityCheckpoint;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityLease;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityProjectionFreshness;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityRecordKind;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityRecordState;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySourceHealth;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncRepository;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncService;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncTransactionPort;
import cn.edu.suda.scholarsense.identityaccess.application.NormalizedIdentityBatch;
import cn.edu.suda.scholarsense.identityaccess.domain.OrganizationNode;
import cn.edu.suda.scholarsense.runtime.IdentityAuthorityRuntimeProfile;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Executes every published negative payload through the real adapter or service. */
class IdentityAuthorityNegativeFixturesTest {
    private static final Instant NOW = Instant.parse("2026-07-24T00:01:00Z");
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "identity-authority",
            "sandbox-0",
            "identity-org");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void everyCatalogCaseExecutesAndProducesItsFrozenReasonCode()
            throws Exception {
        Path base = Path.of(
                "..", "contracts", "identity-authority", "fixtures");
        JsonNode catalog = JSON.readTree(
                Files.readAllBytes(base.resolve(
                        "negative-fixtures-1.0.0.json")));
        JsonNode cases = JSON.readTree(
                        Files.readAllBytes(base.resolve(
                                "negative/payloads-1.0.0.json")))
                .required("cases");
        Map<String, String> expected = new LinkedHashMap<>();
        catalog.required("cases").forEach(value -> expected.put(
                value.required("id").asText(),
                value.required("expectedReasonCode").asText()));

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            JsonNode fixture = cases.required(entry.getKey());
            byte[] body = JSON.writeValueAsBytes(fixture.required("payload"));
            HttpServer server = server(
                    body, fixture.required("detachedSignature").asText());
            try {
                var adapter = adapter(
                        server,
                        fixture.required("signatureVerified").asBoolean());
                if ("adapter".equals(
                        fixture.required("executionStage").asText())) {
                    IdentitySyncException rejected = assertThrows(
                            IdentitySyncException.class,
                            () -> adapter.fetch(
                                    KEY,
                                    fixture.required("afterWatermark").asLong(),
                                    fixture.required("payload")
                                            .required("traceId")
                                            .asText()),
                            entry.getKey());
                    assertEquals(entry.getValue(), rejected.code(), entry.getKey());
                } else {
                    NormalizedIdentityBatch batch = adapter.fetch(
                            KEY,
                            fixture.required("afterWatermark").asLong(),
                            fixture.required("payload")
                                    .required("traceId")
                                    .asText());
                    IdentitySyncException rejected = assertThrows(
                            IdentitySyncException.class,
                            () -> service(fixture).process(batch, lease()),
                            entry.getKey());
                    assertEquals(entry.getValue(), rejected.code(), entry.getKey());
                }
            } finally {
                server.stop(0);
            }
        }
    }

    private static IdentitySyncService service(JsonNode fixture) {
        JsonNode checkpoint = fixture.required("checkpoint");
        IdentityRecordState current = fixture.has("currentRecord")
                ? new IdentityRecordState(
                        fixture.required("currentRecord")
                                .required("sourceVersion")
                                .asLong(),
                        fixture.required("currentRecord")
                                .required("payloadDigest")
                                .asText())
                : null;
        IdentitySyncRepository repository = new IdentitySyncRepository() {
            @Override
            public Optional<IdentityCheckpoint> checkpoint(CheckpointKey key) {
                return Optional.of(new IdentityCheckpoint(
                        key,
                        checkpoint.required("sourceVersion").asLong(),
                        checkpoint.required("sourceWatermark").asLong(),
                        checkpoint.required("aggregateVersion").asLong(),
                        NOW.minusSeconds(30),
                        IdentitySourceHealth.HEALTHY,
                        IdentityProjectionFreshness.FRESH));
            }

            @Override
            public Optional<String> envelopeDigest(UUID batchId) {
                return Optional.empty();
            }

            @Override
            public void apply(
                    NormalizedIdentityBatch batch,
                    IdentityLease lease,
                    Instant appliedAt) {}

            @Override
            public void reject(
                    cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncRejection
                            rejection) {}

            @Override
            public Optional<IdentityRecordState> currentRecord(
                    CheckpointKey key,
                    IdentityRecordKind kind,
                    String externalRefDigest) {
                return Optional.ofNullable(current);
            }

            @Override
            public List<OrganizationNode> currentOrganizations(
                    CheckpointKey key) {
                return List.of();
            }
        };
        return new IdentitySyncService(
                repository,
                (key, from, to, traceId) -> {},
                new IdentitySyncTransactionPort() {
                    @Override
                    public <T> T execute(
                            java.util.function.Supplier<T> work) {
                        return work.get();
                    }
                },
                ignored -> {},
                ignored -> {},
                ignored -> Optional.empty(),
                ignored -> {},
                IdentityAuthorityNegativeFixturesTest::trustedNow);
    }

    private static HttpIdentityAuthoritySourceAdapter adapter(
            HttpServer server, boolean signatureVerified) {
        URI endpoint = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
                        + "/api/v1/incremental");
        var profile = new IdentityAuthorityRuntimeProfile(
                "IDENTITY-AUTHORITY-PROFILE-1.0.0",
                KEY.sourceId(),
                KEY.feedId(),
                KEY.partitionId(),
                KEY.consumerProjection(),
                endpoint,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                "account://test/identity-sync-worker",
                "secret://test/identity-authority-signature",
                "config://test/identity-authority-inbox",
                "config://test/identity-role-mapping-1-0-0",
                "IDENTITY-ROLE-MAPPING-1.0.0",
                "sha256:f09768f88cd6a595791ec6591e65758b85fd8402585c8ffaa053446214895e29",
                Duration.ofSeconds(30),
                5,
                false);
        return new HttpIdentityAuthoritySourceAdapter(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build(),
                JSON,
                profile,
                ignored -> "Bearer controlled-fixture",
                (payload, signature, keyReference) -> signatureVerified,
                (plaintext, purpose) -> new EncryptedSecret(
                        digestBytes(new String(plaintext)
                                .getBytes(StandardCharsets.UTF_8)),
                        digestBytes("wrapped".getBytes(StandardCharsets.UTF_8)),
                        profile.inboxEncryptionKeyReference(),
                        "k1",
                        digestBytes("nonce".getBytes(StandardCharsets.UTF_8))),
                (purpose, raw) -> {
                    String prefix = "identity-actor".equals(purpose)
                            ? "actor"
                            : "external";
                    return prefix + "_v1_k1_" + digest(purpose + "\0" + raw);
                },
                IdentityAuthorityNegativeFixturesTest::trustedNow,
                true);
    }

    private static HttpServer server(byte[] body, String signature)
            throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/incremental", exchange -> {
            exchange.getResponseHeaders().add(
                    "Content-Type", "application/json");
            exchange.getResponseHeaders().add(
                    "X-Identity-Authority-Signature", signature);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static IdentityLease lease() {
        return new IdentityLease(
                KEY,
                UUID.fromString("019c1234-0000-7000-8000-000000000999"),
                1,
                1,
                "fixture-worker",
                NOW.minusSeconds(30),
                NOW.plusSeconds(30));
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

    private static String digest(String value) {
        return HexFormat.of().formatHex(
                digestBytes(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] digestBytes(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
