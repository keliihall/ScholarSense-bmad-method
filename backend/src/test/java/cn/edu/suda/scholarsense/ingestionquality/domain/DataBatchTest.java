package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DataBatchTest {

    private static final UUID BATCH_ID = uuid("019ff100-0000-7000-8000-000000000001");
    private static final UUID LINEAGE_ID = uuid("019ff100-0000-7000-8000-000000000002");
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-09T02:00:00Z");
    private static final Instant SEALED_AT = RECEIVED_AT.plusSeconds(60);
    private static final Instant EVALUATED_AT = SEALED_AT.plusSeconds(30);
    private static final Instant PUBLISHED_AT = EVALUATED_AT.plusSeconds(5);
    private static final String MANIFEST_DIGEST = "sha256:" + "a".repeat(64);
    private static final SealedQualityContractEvidence CONTRACT_EVIDENCE = contractEvidence();

    @Test
    void newBatchHasTheOnlyLegalInitialStateAndKeepsIdentityImmutable() {
        BatchIdentity identity = new BatchIdentity(
                "SRC-P0-STUDENT-001", "student-status:2026-08-09", 7);
        DataBatch batch = DataBatch.receiving(
                BATCH_ID,
                identity,
                BatchLineage.root(LINEAGE_ID, RECEIVED_AT),
                MANIFEST_DIGEST,
                RECEIVED_AT,
                "00112233445566778899aabbccddeeff");

        assertEquals(DataBatchStatus.RECEIVING, batch.status());
        assertEquals(1, batch.aggregateVersion());
        assertEquals(identity, batch.identity());
        assertEquals(MANIFEST_DIGEST, batch.declaredManifestDigest());
        assertEquals(LINEAGE_ID, batch.lineage().lineageId());
        assertNull(batch.manifest());
        assertNull(batch.sealedAt());
        assertNull(batch.evaluatedAt());
        assertNull(batch.publishedAt());
    }

    @Test
    void sealFreezesTheCompleteManifestAndReturnsANewAggregateVersion() {
        DataBatch receiving = receiving();
        BatchManifest manifest = manifest(MANIFEST_DIGEST);

        DataBatch sealed = receiving.seal(manifest, CONTRACT_EVIDENCE, SEALED_AT);

        assertNotSame(receiving, sealed);
        assertEquals(DataBatchStatus.RECEIVING, receiving.status());
        assertNull(receiving.manifest());
        assertEquals(DataBatchStatus.SEALED, sealed.status());
        assertEquals(2, sealed.aggregateVersion());
        assertEquals(manifest, sealed.manifest());
        assertEquals(SEALED_AT, sealed.sealedAt());
        assertThrows(IngestionQualityException.class,
                () -> sealed.seal(manifest, CONTRACT_EVIDENCE, SEALED_AT.plusSeconds(1)));
        assertThrows(IngestionQualityException.class,
                () -> receiving.seal(
                        manifest("sha256:" + "b".repeat(64)), CONTRACT_EVIDENCE, SEALED_AT));
    }

    @Test
    void onlyPassingAssessmentCanPublishAndFailedBatchIsTerminal() {
        DataBatch sealed = receiving().seal(
                manifest(MANIFEST_DIGEST), CONTRACT_EVIDENCE, SEALED_AT);

        DataBatch passed = sealed.recordQualityResult(true, EVALUATED_AT);
        DataBatch published = passed.publish(PUBLISHED_AT);
        DataBatch failed = sealed.recordQualityResult(false, EVALUATED_AT);

        assertEquals(DataBatchStatus.QUALITY_PASSED, passed.status());
        assertEquals(DataBatchStatus.PUBLISHED, published.status());
        assertEquals(PUBLISHED_AT, published.publishedAt());
        assertEquals(DataBatchStatus.QUALITY_FAILED, failed.status());
        assertNull(failed.publishedAt());
        assertThrows(IngestionQualityException.class, () -> sealed.publish(PUBLISHED_AT));
        assertThrows(IngestionQualityException.class, () -> failed.publish(PUBLISHED_AT));
        assertThrows(IngestionQualityException.class,
                () -> published.recordQualityResult(false, PUBLISHED_AT.plusSeconds(1)));
    }

    @Test
    void everyUnapprovedTransitionIsRejectedAndSealEvidenceNeverChanges() {
        DataBatch receiving = receiving();
        DataBatch sealed = receiving.seal(
                manifest(MANIFEST_DIGEST), CONTRACT_EVIDENCE, SEALED_AT);
        DataBatch passed = sealed.recordQualityResult(true, EVALUATED_AT);
        DataBatch failed = sealed.recordQualityResult(false, EVALUATED_AT);
        DataBatch published = passed.publish(PUBLISHED_AT);
        List<DataBatch> states = List.of(receiving, sealed, passed, failed, published);

        for (DataBatch batch : states) {
            if (batch.status() != DataBatchStatus.RECEIVING) {
                assertThrows(IngestionQualityException.class,
                        () -> batch.seal(
                                manifest(MANIFEST_DIGEST), CONTRACT_EVIDENCE, PUBLISHED_AT));
            }
            if (batch.status() != DataBatchStatus.SEALED) {
                assertThrows(IngestionQualityException.class,
                        () -> batch.recordQualityResult(true, PUBLISHED_AT));
            }
            if (batch.status() != DataBatchStatus.QUALITY_PASSED) {
                assertThrows(IngestionQualityException.class,
                        () -> batch.publish(PUBLISHED_AT));
            }
        }
        assertSame(sealed.manifest(), passed.manifest());
        assertSame(passed.manifest(), published.manifest());
        assertSame(sealed.manifest(), failed.manifest());
    }

    @Test
    void stateTimestampsAreMonotonicAndTechnicalFailureHasNoDomainTransition() {
        DataBatch receiving = receiving();
        assertThrows(IngestionQualityException.class,
                () -> receiving.seal(
                        manifest(MANIFEST_DIGEST), CONTRACT_EVIDENCE,
                        RECEIVED_AT.minusNanos(1)));

        DataBatch sealed = receiving.seal(
                manifest(MANIFEST_DIGEST), CONTRACT_EVIDENCE, SEALED_AT);
        assertThrows(IngestionQualityException.class,
                () -> sealed.recordQualityResult(true, SEALED_AT.minusNanos(1)));

        DataBatch passed = sealed.recordQualityResult(true, EVALUATED_AT);
        assertThrows(IngestionQualityException.class,
                () -> passed.publish(EVALUATED_AT.minusNanos(1)));
        assertEquals(DataBatchStatus.SEALED, sealed.status(),
                "a technical error is represented by leaving the sealed aggregate unchanged");
    }

    @Test
    void manifestAndWindowRejectNonCanonicalOrInconsistentEvidence() {
        assertThrows(IngestionQualityException.class,
                () -> new BatchObservationWindow(RECEIVED_AT, RECEIVED_AT));
        assertThrows(IngestionQualityException.class,
                () -> new BatchManifest(
                        10, 8, 1,
                        new BatchObservationWindow(RECEIVED_AT.minusSeconds(3600), RECEIVED_AT),
                        RECEIVED_AT, "Asia/Shanghai", "wm-7",
                        "STUDENT-1.0.0", "sha256:" + "1".repeat(64),
                        "DCC-1.1.0", "sha256:" + "2".repeat(64),
                        "QG-1.0.0", "sha256:" + "3".repeat(64),
                        "QMDP-1.0.0", "sha256:" + "4".repeat(64),
                        RECEIVED_AT.minusSeconds(1), RECEIVED_AT.plusSeconds(60),
                        RECEIVED_AT, "student-daily", MANIFEST_DIGEST));
        assertThrows(IngestionQualityException.class,
                () -> new BatchManifest(
                        DataBatch.MAX_VERSION + 1, DataBatch.MAX_VERSION + 1, 0,
                        new BatchObservationWindow(RECEIVED_AT.minusSeconds(3600), RECEIVED_AT),
                        RECEIVED_AT, "Asia/Shanghai", "wm-7",
                        "STUDENT-1.0.0", "sha256:" + "1".repeat(64),
                        "DCC-1.1.0", "sha256:" + "2".repeat(64),
                        "QG-1.0.0", "sha256:" + "3".repeat(64),
                        "QMDP-1.0.0", "sha256:" + "4".repeat(64),
                        RECEIVED_AT.minusSeconds(1), RECEIVED_AT.plusSeconds(60),
                        RECEIVED_AT, "student-daily", MANIFEST_DIGEST));
        assertThrows(IngestionQualityException.class,
                () -> new BatchManifest(
                        10, 9, 1,
                        new BatchObservationWindow(RECEIVED_AT.minusSeconds(3600), RECEIVED_AT),
                        RECEIVED_AT, "UTC", "wm-7",
                        "STUDENT-1.0.0", "sha256:" + "1".repeat(64),
                        "DCC-1.1.0", "sha256:" + "2".repeat(64),
                        "QG-1.0.0", "sha256:" + "3".repeat(64),
                        "QMDP-1.0.0", "sha256:" + "4".repeat(64),
                        RECEIVED_AT.minusSeconds(1), RECEIVED_AT.plusSeconds(60),
                        RECEIVED_AT, "student-daily", MANIFEST_DIGEST));
    }

    @Test
    void lineageHasClosedRootAndSuccessorShapes() {
        BatchLineage root = BatchLineage.root(LINEAGE_ID, RECEIVED_AT);
        BatchLineage successor = BatchLineage.successor(
                LINEAGE_ID,
                BATCH_ID,
                BatchCorrectionReason.LATE_ARRIVAL,
                RECEIVED_AT.plusSeconds(1));

        assertNull(root.supersedesBatchId());
        assertEquals(RECEIVED_AT, root.effectiveAt());
        assertEquals(root, BatchLineage.root(LINEAGE_ID, RECEIVED_AT));
        assertThrows(IngestionQualityException.class,
                () -> BatchLineage.root(LINEAGE_ID, null));
        assertEquals(BATCH_ID, successor.supersedesBatchId());
        assertEquals(BatchCorrectionReason.LATE_ARRIVAL, successor.reasonCode());
        assertThrows(IngestionQualityException.class,
                () -> BatchLineage.successor(
                        LINEAGE_ID, null, BatchCorrectionReason.SOURCE_CORRECTION, RECEIVED_AT));
    }

    private static DataBatch receiving() {
        return DataBatch.receiving(
                BATCH_ID,
                new BatchIdentity("SRC-P0-STUDENT-001", "student-status:2026-08-09", 7),
                BatchLineage.root(LINEAGE_ID, RECEIVED_AT),
                MANIFEST_DIGEST,
                RECEIVED_AT,
                "00112233445566778899aabbccddeeff");
    }

    private static BatchManifest manifest(String digest) {
        return new BatchManifest(
                10, 9, 1,
                new BatchObservationWindow(RECEIVED_AT.minusSeconds(3600), RECEIVED_AT),
                RECEIVED_AT.plusSeconds(1),
                "Asia/Shanghai",
                "sha256:" + "8".repeat(64),
                "STUDENT-1.0.0",
                "sha256:" + "1".repeat(64),
                "DCC-1.1.0",
                "sha256:" + "2".repeat(64),
                "QG-1.0.0",
                "sha256:" + "3".repeat(64),
                "QMDP-1.0.0",
                "sha256:" + "4".repeat(64),
                RECEIVED_AT.minusSeconds(1),
                RECEIVED_AT.plusSeconds(60),
                RECEIVED_AT,
                "student-daily",
                digest);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static SealedQualityContractEvidence contractEvidence() {
        return new SealedQualityContractEvidence(
                "QMDP-1.0.0", "sha256:" + "1".repeat(64),
                "sha256:" + "2".repeat(64),
                "EXECUTABLE-QUALITY-CONTRACT-LOCK-1.0.0",
                "sha256:" + "3".repeat(64), "sha256:" + "4".repeat(64),
                "AUTH-2026-08-08-001", "AUTH-2026-08-08-001", RECEIVED_AT,
                "QSHM-1.0.0", "sha256:" + "5".repeat(64),
                "sha256:" + "6".repeat(64), "QSHM-CONTRACT-LOCK-1.0.0",
                "sha256:" + "7".repeat(64), "AUTH-2026-08-09-001",
                "AUTH-2026-08-09-001", RECEIVED_AT);
    }
}
