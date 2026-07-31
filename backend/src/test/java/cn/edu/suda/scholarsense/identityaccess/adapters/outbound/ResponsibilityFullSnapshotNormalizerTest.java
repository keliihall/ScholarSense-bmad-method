package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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
