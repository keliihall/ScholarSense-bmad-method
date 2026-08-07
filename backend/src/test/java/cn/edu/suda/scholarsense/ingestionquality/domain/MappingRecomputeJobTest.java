package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MappingRecomputeJobTest {

    private static final Instant NOW = Instant.parse("2026-08-06T08:00:00Z");
    private static final String SUBJECT = "019fcfea-6100-7000-8000-000000000001";

    @Test
    void identityUsesExactlySevenFieldsAndChangesForNewWatermarkOrCorrection() {
        MappingRecomputeIdentity original = identity(
                "019fcfea-6100-7000-8000-000000000010", "sha256:" + "a".repeat(64));
        MappingRecomputeIdentity same = identity(
                "019fcfea-6100-7000-8000-000000000010", "sha256:" + "a".repeat(64));
        MappingRecomputeIdentity newWatermark = identity(
                "019fcfea-6100-7000-8000-000000000010", "sha256:" + "b".repeat(64));
        MappingRecomputeIdentity newCorrection = identity(
                "019fcfea-6100-7000-8000-000000000011", "sha256:" + "a".repeat(64));

        assertEquals(original, same);
        assertNotEquals(original, newWatermark);
        assertNotEquals(original, newCorrection);
        assertEquals(7, MappingRecomputeIdentity.IDENTITY_FIELDS.size());
    }

    @Test
    void attemptsAndFencingAreMonotonicAndStaleWorkersCannotCheckpointOrPublish() {
        MappingRecomputeJob job = MappingRecomputeJob.queued(
                uuid("019fcfea-6100-7000-8000-000000000020"), "SRC-P0-CARD-001", identity(
                        "019fcfea-6100-7000-8000-000000000010", "sha256:" + "a".repeat(64)),
                NOW.plusSeconds(3600), NOW, "00112233445566778899aabbccddeeff");

        long firstFence = job.claim("worker-a", NOW, Duration.ofMinutes(5));
        job.checkpoint(firstFence, 1, NOW.plusSeconds(1));
        job.fail(firstFence, "TRANSIENT_DEPENDENCY", NOW.plusSeconds(2));
        job.requeue(NOW.plusSeconds(3));
        long secondFence = job.claim("worker-b", NOW.plusSeconds(4), Duration.ofMinutes(5));

        assertEquals(2, job.attemptNo());
        assertEquals("SRC-P0-CARD-001", job.ownerSourceId());
        assertTrue(secondFence > firstFence);
        assertThrows(IngestionQualityException.class,
                () -> job.checkpoint(firstFence, 2, NOW.plusSeconds(5)));
        assertThrows(IngestionQualityException.class,
                () -> job.complete(firstFence, NOW.plusSeconds(6)));
        assertEquals(MappingRecomputeResultCode.RECOMPUTED,
                job.complete(secondFence, NOW.plusSeconds(6)));
        assertEquals(MappingRecomputeJobStatus.SUCCEEDED, job.status());
        assertTrue(job.businessPublicationCreated());
    }

    @Test
    void expiryAtTheExactBoundaryStillCorrectsHistoryButSuppressesBusinessPublication() {
        MappingRecomputeJob job = MappingRecomputeJob.queued(
                uuid("019fcfea-6100-7000-8000-000000000021"), "SRC-P0-CARD-001", identity(
                        "019fcfea-6100-7000-8000-000000000010", "sha256:" + "a".repeat(64)),
                NOW, NOW.minusSeconds(60), "00112233445566778899aabbccddeeff");
        long fence = job.claim("worker-a", NOW.minusSeconds(1), Duration.ofMinutes(5));

        assertEquals(MappingRecomputeResultCode.EXPIRED_HISTORY_ONLY, job.complete(fence, NOW));
        assertFalse(job.businessPublicationCreated());
        assertTrue(job.historyCorrected());
    }

    private static MappingRecomputeIdentity identity(String lineage, String watermarkDigest) {
        return new MappingRecomputeIdentity(
                uuid(lineage), SUBJECT, "personal-baseline", "1.2.0",
                "personal-baseline-28d", "baseline-28d-2026-06-29", watermarkDigest);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
