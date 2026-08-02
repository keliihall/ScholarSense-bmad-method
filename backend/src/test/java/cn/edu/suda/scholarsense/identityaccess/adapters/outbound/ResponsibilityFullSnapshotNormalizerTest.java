package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityAuthoritySourcePort;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2LineageManifest;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ResponsibilityFullSnapshotNormalizerTest {
    private static final String SIGNATURE = "snapshot-signature";
    private static final String TRACE =
            "22222222222222222222222222222222";
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "responsibility-authority",
            "sandbox-0",
            "responsibility");
    private final ResponsibilityFullSnapshotNormalizer normalizer =
            new ResponsibilityFullSnapshotNormalizer(
                    new ObjectMapper());

    @Test
    void acceptsSignedSealedCompleteSnapshotAndExplicitEmpty()
            throws Exception {
        var snapshot = normalize(validFixture(), LocalDate.of(
                2026, 7, 30), TRACE);
        assertTrue(snapshot.sealed());
        assertTrue(snapshot.complete());
        assertEquals(1, snapshot.expectedCount());
        assertEquals(7, snapshot.throughWatermark());
        assertEquals(
                "a".repeat(64),
                snapshot.entries().getFirst().studentSourceRefDigest());

        String empty = Files.readString(Path.of(
                "..",
                "contracts",
                "responsibility-authority",
                "fixtures",
                "valid",
                "empty-complete-snapshot.json"));
        String signatureDigest =
                ResponsibilityAuthorityNormalizer.digest(
                        SIGNATURE.getBytes(StandardCharsets.UTF_8));
        var normalizedEmpty = normalize(
                empty.replace(
                                "sha256:" + "8".repeat(64),
                                "sha256:" + signatureDigest)
                        .getBytes(StandardCharsets.UTF_8),
                LocalDate.of(2026, 7, 29),
                "33333333333333333333333333333333");
        assertEquals(0, normalizedEmpty.expectedCount());
        assertTrue(normalizedEmpty.entries().isEmpty());
    }

    @Test
    void partialUnsealedMissingPartitionAndDigestFailClosed()
            throws Exception {
        assertReason(
                new String(validFixture(), StandardCharsets.UTF_8)
                        .replace("\"complete\": true",
                                "\"complete\": false"),
                "RESPONSIBILITY_SNAPSHOT_PARTIAL");
        assertReason(
                new String(validFixture(), StandardCharsets.UTF_8)
                        .replace("\"sealed\": true",
                                "\"sealed\": false"),
                "RESPONSIBILITY_SNAPSHOT_UNSEALED");
        assertReason(
                new String(validFixture(), StandardCharsets.UTF_8)
                        .replace(
                                "\"partitions\": [\"sandbox-0\"]",
                                "\"partitions\": [\"other-0\"]"),
                "RESPONSIBILITY_SNAPSHOT_PARTITION_MISSING");
        assertReason(
                new String(validFixture(), StandardCharsets.UTF_8)
                        .replace(
                                "sha256:268673b88ad65f7c5e8b68016eddb33d955677863cbf0a5ecaa9835bb2768d9e",
                                "sha256:" + "0".repeat(64)),
                "RESPONSIBILITY_SNAPSHOT_DIGEST_MISMATCH");
    }

    @Test
    void acceptsRequestedV2SnapshotAndRejectsAResponseVersionMismatch()
            throws Exception {
        byte[] version2 = validV2Fixture();

        var snapshot = normalizer.normalize(
                version2,
                SIGNATURE,
                true,
                ResponsibilityAuthoritySourcePort.VERSION_2,
                KEY,
                LocalDate.of(2026, 7, 30),
                TRACE);

        assertEquals(
                ResponsibilityAuthoritySourcePort.VERSION_2,
                snapshot.contractVersion());
        assertEquals(1, snapshot.lineageManifests().size());

        IdentitySyncException mismatch = assertThrows(
                IdentitySyncException.class,
                () -> normalizer.normalize(
                        validFixture(),
                        SIGNATURE,
                        true,
                        ResponsibilityAuthoritySourcePort.VERSION_2,
                        KEY,
                        LocalDate.of(2026, 7, 30),
                        TRACE));
        assertEquals(
                "RESPONSIBILITY_SOURCE_CONTRACT_VERSION_MISMATCH",
                mismatch.code());
    }

    private void assertReason(String body, String reasonCode) {
        IdentitySyncException failure = assertThrows(
                IdentitySyncException.class,
                () -> normalize(
                        body.getBytes(StandardCharsets.UTF_8),
                        LocalDate.of(2026, 7, 30),
                        TRACE));
        assertEquals(reasonCode, failure.code());
    }

    private byte[] validFixture() throws Exception {
        String value = Files.readString(Path.of(
                "..",
                "contracts",
                "responsibility-authority",
                "fixtures",
                "valid",
                "full-snapshot.json"));
        return value
                .replace(
                        "sha256:" + "6".repeat(64),
                        "sha256:268673b88ad65f7c5e8b68016eddb33d955677863cbf0a5ecaa9835bb2768d9e")
                .replace(
                        "sha256:" + "7".repeat(64),
                        "sha256:"
                                + ResponsibilityAuthorityNormalizer.digest(
                                        SIGNATURE.getBytes(
                                                StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] validV2Fixture() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ObjectNode root = (ObjectNode) json.readTree(validFixture());
        root.put(
                "contractVersion",
                ResponsibilityAuthoritySourcePort.VERSION_2);
        root.put(
                "lineageDigestProfile",
                "RESPONSIBILITY-LINEAGE-DIGEST-1.0.0");
        var lineage = new ResponsibilityV2LineageManifest(
                "rtok_CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC",
                new AccessInvalidationLineageId(
                        "lin_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
                java.util.UUID.fromString(
                        "019c0000-0000-7000-8000-000000000201"),
                java.util.UUID.fromString(
                        "019c0000-0000-7000-8000-000000000201"),
                1,
                "b".repeat(64));
        root.put("lineageCount", 1);
        root.put(
                "canonicalLineageDigest",
                "sha256:" + ResponsibilityV2LineageManifest.digest(
                        java.util.List.of(lineage)));
        ObjectNode lineageJson = root.putArray("lineages").addObject();
        lineageJson.put("relationRefToken", lineage.relationRefToken());
        lineageJson.put("lineageId", lineage.lineageId().value());
        lineageJson.put("rootEventId", lineage.rootEventId().toString());
        lineageJson.put("headEventId", lineage.headEventId().toString());
        lineageJson.put("eventCount", lineage.eventCount());
        lineageJson.put(
                "canonicalChainDigest",
                "sha256:" + lineage.canonicalChainDigest());
        return json.writeValueAsBytes(root);
    }

    private cn.edu.suda.scholarsense.identityaccess.application
            .ResponsibilityFullSnapshot normalize(
                    byte[] body,
                    LocalDate businessDate,
                    String traceId) {
        return normalizer.normalize(
                body,
                SIGNATURE,
                true,
                KEY,
                businessDate,
                traceId);
    }
}
