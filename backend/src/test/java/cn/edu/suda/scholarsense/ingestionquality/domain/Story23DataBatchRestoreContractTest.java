package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Persistence hydration contract for Story 2.3's complete batch state machine. */
class Story23DataBatchRestoreContractTest {
    private static final UUID BATCH_ID = uuid("019ff310-0000-7000-8000-000000000001");
    private static final UUID LINEAGE_ID = uuid("019ff310-0000-7000-8000-000000000002");
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-09T02:00:00Z");
    private static final Instant SEALED_AT = RECEIVED_AT.plusSeconds(60);
    private static final Instant EVALUATED_AT = SEALED_AT.plusSeconds(30);
    private static final Instant PUBLISHED_AT = EVALUATED_AT.plusSeconds(5);
    private static final String MANIFEST_DIGEST = "sha256:" + "a".repeat(64);
    private static final String TRACE_ID = "00112233445566778899aabbccddeeff";

    @Test
    void restoresEveryPersistedLifecycleShapeWithoutReplayingTransitions() {
        DataBatch receiving = DataBatch.receiving(
                BATCH_ID, identity(), BatchLineage.root(LINEAGE_ID, RECEIVED_AT),
                MANIFEST_DIGEST, RECEIVED_AT, TRACE_ID);
        DataBatch sealed = receiving.seal(manifest(RECEIVED_AT), evidence(), SEALED_AT);
        DataBatch passed = sealed.recordQualityResult(true, EVALUATED_AT);
        DataBatch failed = sealed.recordQualityResult(false, EVALUATED_AT);
        DataBatch published = passed.publish(PUBLISHED_AT);

        for (DataBatch expected : List.of(receiving, sealed, passed, failed, published)) {
            DataBatch restored = restore(
                    expected.batchId(), expected.identity(), expected.lineage(),
                    expected.declaredManifestDigest(), expected.traceId(), expected.status(),
                    expected.aggregateVersion(), expected.manifest(),
                    expected.sealedQualityContractEvidence(), expected.receivedAt(),
                    expected.sealedAt(), expected.evaluatedAt(), expected.publishedAt());

            assertAll(expected.status().name(),
                    () -> assertEquals(expected.batchId(), restored.batchId()),
                    () -> assertEquals(expected.identity(), restored.identity()),
                    () -> assertEquals(expected.lineage(), restored.lineage()),
                    () -> assertEquals(
                            expected.declaredManifestDigest(), restored.declaredManifestDigest()),
                    () -> assertEquals(expected.traceId(), restored.traceId()),
                    () -> assertEquals(expected.status(), restored.status()),
                    () -> assertEquals(expected.aggregateVersion(), restored.aggregateVersion()),
                    () -> assertEquals(expected.manifest(), restored.manifest()),
                    () -> assertEquals(
                            expected.sealedQualityContractEvidence(),
                            restored.sealedQualityContractEvidence()),
                    () -> assertEquals(expected.receivedAt(), restored.receivedAt()),
                    () -> assertEquals(expected.sealedAt(), restored.sealedAt()),
                    () -> assertEquals(expected.evaluatedAt(), restored.evaluatedAt()),
                    () -> assertEquals(expected.publishedAt(), restored.publishedAt()));
        }
    }

    @Test
    void restoreRejectsStatusVersionAndTimestampShapesThatTheTableCannotStore() {
        BatchLineage lineage = BatchLineage.root(LINEAGE_ID, RECEIVED_AT);
        BatchManifest manifest = manifest(RECEIVED_AT);
        SealedQualityContractEvidence evidence = evidence();

        assertAll(
                () -> assertThrows(IngestionQualityException.class, () -> restore(
                        BATCH_ID, identity(), lineage, MANIFEST_DIGEST, TRACE_ID,
                        DataBatchStatus.RECEIVING, 1, manifest, evidence,
                        RECEIVED_AT, null, null, null)),
                () -> assertThrows(IngestionQualityException.class, () -> restore(
                        BATCH_ID, identity(), lineage, MANIFEST_DIGEST, TRACE_ID,
                        DataBatchStatus.SEALED, 7, manifest, evidence,
                        RECEIVED_AT, SEALED_AT, null, null)),
                () -> assertThrows(IngestionQualityException.class, () -> restore(
                        BATCH_ID, identity(), lineage, MANIFEST_DIGEST, TRACE_ID,
                        DataBatchStatus.QUALITY_PASSED, 3, manifest, evidence,
                        RECEIVED_AT, SEALED_AT, null, null)),
                () -> assertThrows(IngestionQualityException.class, () -> restore(
                        BATCH_ID, identity(), lineage, MANIFEST_DIGEST, TRACE_ID,
                        DataBatchStatus.PUBLISHED, 4, manifest, evidence,
                        RECEIVED_AT, SEALED_AT, EVALUATED_AT,
                        EVALUATED_AT.minusNanos(1))),
                () -> assertThrows(IngestionQualityException.class, () -> restore(
                        BATCH_ID, identity(), lineage, MANIFEST_DIGEST, TRACE_ID,
                        DataBatchStatus.SEALED, 2, manifest(RECEIVED_AT.plusSeconds(1)), evidence,
                        RECEIVED_AT, SEALED_AT, null, null)));
    }

    private static DataBatch restore(
            UUID batchId,
            BatchIdentity identity,
            BatchLineage lineage,
            String declaredManifestDigest,
            String traceId,
            DataBatchStatus status,
            long aggregateVersion,
            BatchManifest manifest,
            SealedQualityContractEvidence evidence,
            Instant receivedAt,
            Instant sealedAt,
            Instant evaluatedAt,
            Instant publishedAt) {
        try {
            Method restore = DataBatch.class.getDeclaredMethod(
                    "restore", UUID.class, BatchIdentity.class, BatchLineage.class,
                    String.class, String.class, DataBatchStatus.class, long.class,
                    BatchManifest.class, SealedQualityContractEvidence.class,
                    Instant.class, Instant.class, Instant.class, Instant.class);
            assertTrue(Modifier.isStatic(restore.getModifiers()), "restore must be static");
            assertEquals(DataBatch.class, restore.getReturnType());
            restore.setAccessible(true);
            return (DataBatch) restore.invoke(
                    null, batchId, identity, lineage, declaredManifestDigest, traceId, status,
                    aggregateVersion, manifest, evidence, receivedAt, sealedAt, evaluatedAt,
                    publishedAt);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError("DataBatch.restore failed", failure.getCause());
        } catch (ReflectiveOperationException missingApi) {
            throw new AssertionError(
                    "Story 2.3 requires DataBatch.restore with the complete persisted shape",
                    missingApi);
        }
    }

    private static BatchIdentity identity() {
        return new BatchIdentity(
                "SRC-P0-STUDENT-001",
                "student-status:" + Character.toString(0) + ":2026-08-09",
                7);
    }

    private static BatchManifest manifest(Instant receivedAt) {
        return new BatchManifest(
                10, 9, 1,
                new BatchObservationWindow(RECEIVED_AT.minusSeconds(3600), RECEIVED_AT),
                RECEIVED_AT.plusSeconds(1), "Asia/Shanghai",
                "sha256:" + "8".repeat(64),
                "STUDENT-1.0.0", "sha256:" + "1".repeat(64),
                "DCC-1.1.0", "sha256:" + "2".repeat(64),
                "QG-1.0.0", "sha256:" + "3".repeat(64),
                "QMDP-1.0.0", "sha256:" + "4".repeat(64),
                RECEIVED_AT.minusSeconds(1), RECEIVED_AT.plusSeconds(60), receivedAt,
                "student-daily", MANIFEST_DIGEST);
    }

    private static SealedQualityContractEvidence evidence() {
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

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
