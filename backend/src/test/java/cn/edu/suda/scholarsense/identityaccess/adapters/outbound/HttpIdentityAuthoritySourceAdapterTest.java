package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityArchivedEnvelope;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuthorityReferencePort;
import cn.edu.suda.scholarsense.identityaccess.application.PseudonymizationPort;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HttpIdentityAuthoritySourceAdapterTest {
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final String SIGNATURE = "sandbox-signature-v1";
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001", "identity-authority", "sandbox-0", "identity-org");

    @Test
    void consumesSignedSandboxBatchWithoutRetainingRawIdentifiers() throws Exception {
        byte[] body = fixture();
        var authorization = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        HttpServer server = server(body, authorization, query);
        try {
            var adapter = adapter(server, true);

            var batch = adapter.fetch(KEY, 6, TRACE);

            assertEquals("Bearer sandbox-workload", authorization.get());
            assertEquals("afterWatermark=6&consumerProjection=identity-org", query.get());
            assertTrue(batch.signatureVerified());
            assertEquals(1, batch.accounts().size());
            assertEquals(1, batch.organizations().size());
            assertEquals(1, batch.roleBindings().size());
            assertEquals(
                    "actor_v1_k1_" + digest("https://idp.sandbox.invalid\0subject-001"),
                    batch.accounts().getFirst().subjectBindingToken());
            assertFalse(batch.accounts().getFirst().toString().contains("ACCOUNT-001"));
            assertFalse(batch.accounts().getFirst().toString().contains("subject-001"));
            assertEquals("config://test/identity-authority-inbox", batch.encryptionKeyRef());
            assertTrue(batch.wrappedDataKey().length > 0);
            assertTrue(batch.encryptionNonce().length > 0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidSignatureUnknownRoleAndUnapprovedConditionsFailClosed() throws Exception {
        byte[] body = fixture();
        HttpServer server = server(body, new AtomicReference<>(), new AtomicReference<>());
        try {
            var unsigned = adapter(server, false).fetch(KEY, 6, TRACE);
            assertFalse(unsigned.signatureVerified());
        } finally {
            server.stop(0);
        }
        String unknown = new String(body, StandardCharsets.UTF_8)
                .replace("SANDBOX_STUDENT_AFFAIRS", "UNAPPROVED_ROLE")
                .replace(
                        "d4634374e2287315850e54856726b436e1e20569d9637bca6a75e49954978401",
                        "770fa4a2e223a0352368b68a13ef9b51d1f4b4c170e8324f67b1b6566eda5812");
        HttpServer unknownServer = server(
                unknown.getBytes(StandardCharsets.UTF_8),
                new AtomicReference<>(),
                new AtomicReference<>());
        try {
            IdentitySyncException rejected = assertThrows(
                    IdentitySyncException.class,
                    () -> adapter(unknownServer, true).fetch(KEY, 6, TRACE));
            assertEquals("IDENTITY_ROLE_UNKNOWN", rejected.code());
        } finally {
            unknownServer.stop(0);
        }
        String wrongOrganization = new String(body, StandardCharsets.UTF_8)
                .replace("\"organizationType\": \"school\"",
                        "\"organizationType\": \"college\"")
                .replace(
                        "17c6a8d487e58a34e5815fe4d5d7a83f496d3a2b1b9cb793fe85d0a957f62dc4",
                        "f17d2db0a65871ca3bde2f5a54f6b7cd1364714bd4f7e0e0bf2a9ee7c8bae16f");
        HttpServer conditionServer = server(
                wrongOrganization.getBytes(StandardCharsets.UTF_8),
                new AtomicReference<>(),
                new AtomicReference<>());
        try {
            IdentitySyncException rejected = assertThrows(
                    IdentitySyncException.class,
                    () -> adapter(conditionServer, true).fetch(KEY, 6, TRACE));
            assertEquals("IDENTITY_ROLE_MAPPING_UNAPPROVED", rejected.code());
        } finally {
            conditionServer.stop(0);
        }
    }

    @Test
    void consumesSignedHeartbeatAsSuccessfulNoChangeResult() throws Exception {
        byte[] body = heartbeatFixture();
        HttpServer server = server(body, new AtomicReference<>(), new AtomicReference<>());
        try {
            var result = adapter(server, true).fetch(KEY, 6, TRACE);

            assertTrue(result.noChange());
            assertTrue(result.signatureVerified());
            assertEquals(6, result.fromWatermark());
            assertEquals(6, result.toWatermark());
            assertTrue(result.sourceFacts().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rebuildNormalizerConsumesADecryptedVerifiedArchive() throws Exception {
        byte[] body = fixture();
        HttpServer server =
                server(body, new AtomicReference<>(), new AtomicReference<>());
        try {
            var adapter = adapter(server, true);
            var original = adapter.fetch(KEY, 6, TRACE);
            var archive = new IdentityArchivedEnvelope(
                    original.batchId(),
                    original.key(),
                    original.schemaVersion(),
                    original.sourceVersion(),
                    original.fromWatermark(),
                    original.toWatermark(),
                    original.sourceVisibleAt(),
                    original.observedAt(),
                    original.observedAt(),
                    original.traceId(),
                    original.mappingVersion(),
                    original.mappingDigest(),
                    original.envelopeDigest(),
                    original.signatureDigest(),
                    new EncryptedSecret(
                            original.encryptedEnvelope(),
                            original.wrappedDataKey(),
                            original.encryptionKeyRef(),
                            original.encryptionKeyVersion(),
                            original.encryptionNonce()));
            IdentityAuthorityReferencePort noReferences =
                    new IdentityAuthorityReferencePort() {
                        @Override
                        public Optional<cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeAccount>
                                findAccount(
                                        CheckpointKey key,
                                        String externalRefDigest) {
                            return Optional.empty();
                        }

                        @Override
                        public Optional<cn.edu.suda.scholarsense.identityaccess.domain.OrganizationNode>
                                findOrganization(
                                        CheckpointKey key,
                                        String externalRefDigest) {
                            return Optional.empty();
                        }
                    };

            var rebuilt = adapter.normalize(
                    archive,
                    new String(body, StandardCharsets.UTF_8).toCharArray(),
                    noReferences);

            assertEquals(original.batchId(), rebuilt.batchId());
            assertEquals(original.sourceFacts().size(), rebuilt.sourceFacts().size());
            assertTrue(rebuilt.signatureVerified());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exactReplayRangeIsSentToTheUpstreamContract() throws Exception {
        var query = new AtomicReference<String>();
        HttpServer server = server(fixture(), new AtomicReference<>(), query);
        try {
            var result = adapter(server, true).fetchRange(KEY, 7, 7, TRACE);

            assertEquals(7, result.toWatermark());
            assertEquals(
                    "afterWatermark=6&throughWatermark=7&consumerProjection=identity-org",
                    query.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void identityActorKeyRotationWritesCurrentAndCarriesHistoricalReadToken()
            throws Exception {
        HttpServer server =
                server(fixture(), new AtomicReference<>(), new AtomicReference<>());
        PseudonymizationPort rotating = new PseudonymizationPort() {
            @Override
            public String pseudonymize(String purpose, String raw) {
                String prefix = "identity-actor".equals(purpose) ? "actor" : "external";
                String key = "identity-actor".equals(purpose) ? "k2" : "k1";
                return prefix + "_v1_" + key + "_" + digest(raw);
            }

            @Override
            public List<String> pseudonymizeForRead(String purpose, String raw) {
                String current = pseudonymize(purpose, raw);
                return "identity-actor".equals(purpose)
                        ? List.of(
                                current,
                                "actor_v1_k1_" + digest(raw))
                        : List.of(current);
            }
        };
        try {
            var batch = adapter(server, true, rotating).fetch(KEY, 6, TRACE);

            assertTrue(batch.accounts().getFirst()
                    .subjectBindingToken().startsWith("actor_v1_k2_"));
            assertEquals(
                    List.of("k2", "k1"),
                    batch.accounts().getFirst().subjectBindingReadTokens().stream()
                            .map(token -> token.substring(
                                    token.indexOf("_k") + 1,
                                    token.indexOf('_', token.indexOf("_k") + 2)))
                            .toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsPayloadDigestAndEnvelopePayloadIntervalMismatchBeforeNormalization()
            throws Exception {
        String valid = new String(fixture(), StandardCharsets.UTF_8);
        byte[] changedPayload = valid.replace(
                        "\"subject\": \"subject-001\"",
                        "\"subject\": \"subject-tampered\"")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer digestServer =
                server(changedPayload, new AtomicReference<>(), new AtomicReference<>());
        try {
            IdentitySyncException rejected = assertThrows(
                    IdentitySyncException.class,
                    () -> adapter(digestServer, true).fetch(KEY, 6, TRACE));
            assertEquals("IDENTITY_SOURCE_PAYLOAD_DIGEST_INVALID", rejected.code());
        } finally {
            digestServer.stop(0);
        }

        byte[] mismatchedWindow = replaceFirstLiteral(
                        valid,
                        "\"effectiveFrom\": \"2026-07-24T00:00:00Z\"",
                        "\"effectiveFrom\": \"2026-07-23T23:59:00Z\"")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer intervalServer =
                server(mismatchedWindow, new AtomicReference<>(), new AtomicReference<>());
        try {
            IdentitySyncException rejected = assertThrows(
                    IdentitySyncException.class,
                    () -> adapter(intervalServer, true).fetch(KEY, 6, TRACE));
            assertEquals("IDENTITY_SOURCE_EFFECTIVE_INTERVAL_MISMATCH", rejected.code());
        } finally {
            intervalServer.stop(0);
        }
    }

    @Test
    void rejectsOversizedBodyBeforeReadingItWithoutABound() throws Exception {
        byte[] oversized = new byte[4 * 1024 * 1024 + 1];
        HttpServer server =
                server(oversized, new AtomicReference<>(), new AtomicReference<>());
        try {
            IdentitySyncException rejected = assertThrows(
                    IdentitySyncException.class,
                    () -> adapter(server, true).fetch(KEY, 6, TRACE));
            assertEquals("IDENTITY_SOURCE_PAYLOAD_TOO_LARGE", rejected.code());
        } finally {
            server.stop(0);
        }
    }

    private static HttpIdentityAuthoritySourceAdapter adapter(
            HttpServer server, boolean signatureValid) {
        return adapter(server, signatureValid, (purpose, raw) -> {
            String prefix = "identity-actor".equals(purpose) ? "actor" : "external";
            return prefix + "_v1_k1_" + digest(raw);
        });
    }

    private static HttpIdentityAuthoritySourceAdapter adapter(
            HttpServer server,
            boolean signatureValid,
            PseudonymizationPort pseudonyms) {
        URI endpoint = URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/incremental");
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
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(),
                profile,
                ignored -> "Bearer sandbox-workload",
                (payload, signature, keyReference) ->
                        signatureValid
                                && SIGNATURE.equals(signature)
                                && profile.signatureKeyReference().equals(keyReference),
                (plaintext, purpose) -> new EncryptedSecret(
                        "ciphertext".getBytes(StandardCharsets.UTF_8),
                        "wrapped-key".getBytes(StandardCharsets.UTF_8),
                        profile.inboxEncryptionKeyReference(),
                        "k1",
                        "nonce".getBytes(StandardCharsets.UTF_8)),
                pseudonyms,
                HttpIdentityAuthoritySourceAdapterTest::trustedNow,
                true);
    }

    private static HttpServer server(
            byte[] body,
            AtomicReference<String> authorization,
            AtomicReference<String> query) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/incremental", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            query.set(exchange.getRequestURI().getQuery());
            exchange.getResponseHeaders().add(
                    "Content-Type", "application/json");
            exchange.getResponseHeaders().add(
                    "X-Identity-Authority-Signature", SIGNATURE);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static byte[] fixture() throws Exception {
        String value = Files.readString(Path.of(
                "..", "contracts", "identity-authority", "fixtures", "valid",
                "incremental-batch.json"));
        String declared = "sha256:8f741c8be556e77c3ea742f642ecaed5b458e8318548aef65e0d99d9e1e4b0a1";
        return value.replace(declared, "sha256:" + digest(SIGNATURE))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] heartbeatFixture() throws Exception {
        String value = Files.readString(Path.of(
                "..", "contracts", "identity-authority", "fixtures", "valid",
                "heartbeat.json"));
        return value.replace("sha256:" + "0".repeat(64), "sha256:" + digest(SIGNATURE))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String replaceFirstLiteral(
            String value, String target, String replacement) {
        int index = value.indexOf(target);
        if (index < 0) {
            throw new IllegalArgumentException("test fixture target missing");
        }
        return value.substring(0, index)
                + replacement
                + value.substring(index + target.length());
    }

    private static TrustedTime trustedNow() {
        Instant now = Instant.parse("2026-07-24T00:01:00Z");
        return new TrustedTime(
                now,
                new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        now.minusSeconds(10),
                        now.plusSeconds(50),
                        "evidence://signed/clock/campus-ntp-a.json"));
    }
}
