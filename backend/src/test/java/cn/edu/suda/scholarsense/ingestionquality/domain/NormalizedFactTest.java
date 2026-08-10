package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NormalizedFactTest {

    private static final UUID BATCH_ID = uuid("019ff300-0000-7000-8000-000000000001");
    private static final UUID LINEAGE_ID = uuid("019ff300-0000-7000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-09T04:00:00Z");
    private static final String MANIFEST_DIGEST = "sha256:" + "a".repeat(64);
    private static final SealedQualityContractEvidence CONTRACT_EVIDENCE = contractEvidence();

    @Test
    void exactRecordReplayDeduplicatesButChangedContentConflicts() {
        DataBatch receiving = receiving();
        NormalizedFact fact = fact("record-1", "sha256:" + "b".repeat(64));
        NormalizedFactSet one = NormalizedFactSet.empty(receiving).accept(receiving, fact);

        NormalizedFactSet replay = one.accept(receiving, fact);

        assertSame(one, replay);
        assertEquals(List.of(fact), replay.facts());
        IngestionQualityException conflict = assertThrows(
                IngestionQualityException.class,
                () -> one.accept(receiving,
                        fact("record-1", "sha256:" + "c".repeat(64))));
        assertEquals("INGESTION_QUALITY_NORMALIZED_FACT_CONFLICT", conflict.code());
    }

    @Test
    void factMustMatchItsOwningBatchSourceVersionAndLineage() {
        DataBatch receiving = receiving();
        List<NormalizedFact> invalid = List.of(
                new NormalizedFact(
                        identity(uuid("019ff300-0000-7000-8000-000000000099"),
                                "record-1", "SRC-P0-STUDENT-001", 7, LINEAGE_ID),
                        "sha256:" + "b".repeat(64)),
                new NormalizedFact(
                        identity(BATCH_ID, "record-1", "SRC-P0-CARD-001", 7, LINEAGE_ID),
                        "sha256:" + "b".repeat(64)),
                new NormalizedFact(
                        identity(BATCH_ID, "record-1", "SRC-P0-STUDENT-001", 8, LINEAGE_ID),
                        "sha256:" + "b".repeat(64)),
                new NormalizedFact(
                        identity(BATCH_ID, "record-1", "SRC-P0-STUDENT-001", 7,
                                uuid("019ff300-0000-7000-8000-000000000098")),
                        "sha256:" + "b".repeat(64)));

        for (NormalizedFact candidate : invalid) {
            IngestionQualityException failure = assertThrows(
                    IngestionQualityException.class,
                    () -> NormalizedFactSet.empty(receiving).accept(receiving, candidate));
            assertEquals("INGESTION_QUALITY_NORMALIZED_FACT_BINDING_INVALID", failure.code());
        }
    }

    @Test
    void sourceSchemaBindingIsCheckedAgainstTheFrozenSealManifest() {
        DataBatch receiving = receiving();
        DataBatch sealed = receiving.seal(manifest(), CONTRACT_EVIDENCE, NOW.plusSeconds(1));
        NormalizedFactSet valid = NormalizedFactSet.empty(receiving)
                .accept(receiving, fact("record-1", "sha256:" + "b".repeat(64)))
                .accept(receiving, fact("record-2", "sha256:" + "d".repeat(64)));
        NormalizedFact wrongSchema = new NormalizedFact(
                new NormalizedFactIdentity(
                        BATCH_ID, "record-2", "SRC-P0-STUDENT-001",
                        "student-status:2026-08-09", 7,
                        "STUDENT-2.0.0", "sha256:" + "9".repeat(64), LINEAGE_ID),
                "sha256:" + "c".repeat(64));
        NormalizedFactSet invalid = NormalizedFactSet.empty(receiving)
                .accept(receiving, fact("record-1", "sha256:" + "b".repeat(64)))
                .accept(receiving, wrongSchema);

        assertNotSame(valid, valid.validatedForSeal(sealed));
        IngestionQualityException failure = assertThrows(
                IngestionQualityException.class, () -> invalid.validatedForSeal(sealed));
        assertEquals("INGESTION_QUALITY_NORMALIZED_FACT_BINDING_INVALID", failure.code());
    }

    @Test
    void factsCanOnlyBeAppendedWhileReceivingAndAreVisibleAsAWholeAfterPublish() {
        DataBatch receiving = receiving();
        NormalizedFact first = fact("record-1", "sha256:" + "b".repeat(64));
        NormalizedFact second = fact("record-2", "sha256:" + "c".repeat(64));
        NormalizedFactSet openFacts = NormalizedFactSet.empty(receiving)
                .accept(receiving, first)
                .accept(receiving, second);
        DataBatch sealed = receiving.seal(manifest(), CONTRACT_EVIDENCE, NOW.plusSeconds(1));
        NormalizedFactSet facts = openFacts.validatedForSeal(sealed);
        DataBatch passed = sealed.recordQualityResult(true, NOW.plusSeconds(2));
        DataBatch failed = sealed.recordQualityResult(false, NOW.plusSeconds(2));
        DataBatch published = passed.publish(NOW.plusSeconds(3));

        assertTrue(facts.visibleFacts(receiving).isEmpty());
        assertTrue(facts.visibleFacts(sealed).isEmpty());
        assertTrue(facts.visibleFacts(passed).isEmpty());
        assertTrue(facts.visibleFacts(failed).isEmpty());
        assertEquals(List.of(first, second), facts.visibleFacts(published));
        IngestionQualityException immutable = assertThrows(
                IngestionQualityException.class,
                () -> facts.accept(sealed, fact("record-3", "sha256:" + "d".repeat(64))));
        assertEquals("INGESTION_QUALITY_BATCH_IMMUTABLE", immutable.code());
        IngestionQualityException staleReceiving = assertThrows(
                IngestionQualityException.class,
                () -> facts.accept(receiving, fact("record-3", "sha256:" + "d".repeat(64))));
        assertEquals("INGESTION_QUALITY_BATCH_IMMUTABLE", staleReceiving.code());
    }

    @Test
    void publicationRejectsAnUnfrozenWrongSchemaOrIncompleteFactSet() {
        DataBatch receiving = receiving();
        DataBatch sealed = receiving.seal(manifest(), CONTRACT_EVIDENCE, NOW.plusSeconds(1));
        DataBatch published = sealed.recordQualityResult(true, NOW.plusSeconds(2))
                .publish(NOW.plusSeconds(3));
        NormalizedFactSet incomplete = NormalizedFactSet.empty(receiving)
                .accept(receiving, fact("record-1", "sha256:" + "b".repeat(64)));
        NormalizedFact wrongSchema = new NormalizedFact(
                new NormalizedFactIdentity(
                        BATCH_ID, "record-2", "SRC-P0-STUDENT-001",
                        "student-status:2026-08-09", 7,
                        "STUDENT-2.0.0", "sha256:" + "9".repeat(64), LINEAGE_ID),
                "sha256:" + "c".repeat(64));
        NormalizedFactSet wrong = incomplete.accept(receiving, wrongSchema);

        for (NormalizedFactSet invalid : List.of(incomplete, wrong)) {
            IngestionQualityException failure = assertThrows(
                    IngestionQualityException.class,
                    () -> invalid.visibleFacts(published));
            assertEquals("INGESTION_QUALITY_NORMALIZED_FACT_BINDING_INVALID", failure.code());
        }
        IngestionQualityException countMismatch = assertThrows(
                IngestionQualityException.class,
                () -> incomplete.validatedForSeal(sealed));
        assertEquals("INGESTION_QUALITY_NORMALIZED_FACT_BINDING_INVALID", countMismatch.code());
    }

    @Test
    void factSetCannotBeReboundToAnotherOwnerThatReusesTheBatchId() {
        DataBatch owner = receiving();
        NormalizedFactSet facts = NormalizedFactSet.empty(owner)
                .accept(owner, fact("record-1", "sha256:" + "b".repeat(64)))
                .accept(owner, fact("record-2", "sha256:" + "c".repeat(64)))
                .validatedForSeal(owner.seal(
                        manifest(), CONTRACT_EVIDENCE, NOW.plusSeconds(1)));
        DataBatch foreign = DataBatch.receiving(
                        BATCH_ID,
                        new BatchIdentity("SRC-P0-CARD-001", "card:2026-08-09", 7),
                        BatchLineage.root(
                                uuid("019ff300-0000-7000-8000-000000000099"), NOW),
                        MANIFEST_DIGEST,
                        NOW,
                        "ffeeddccbbaa99887766554433221100")
                .seal(manifest(), CONTRACT_EVIDENCE, NOW.plusSeconds(1))
                .recordQualityResult(true, NOW.plusSeconds(2))
                .publish(NOW.plusSeconds(3));

        IngestionQualityException mismatch = assertThrows(
                IngestionQualityException.class,
                () -> facts.visibleFacts(foreign));
        assertEquals("INGESTION_QUALITY_NORMALIZED_FACT_BINDING_INVALID", mismatch.code());
    }

    private static DataBatch receiving() {
        return DataBatch.receiving(
                BATCH_ID,
                new BatchIdentity("SRC-P0-STUDENT-001", "student-status:2026-08-09", 7),
                BatchLineage.root(LINEAGE_ID, NOW),
                MANIFEST_DIGEST,
                NOW,
                "00112233445566778899aabbccddeeff");
    }

    private static NormalizedFact fact(String recordId, String digest) {
        return new NormalizedFact(
                identity(BATCH_ID, recordId, "SRC-P0-STUDENT-001", 7, LINEAGE_ID),
                digest);
    }

    private static NormalizedFactIdentity identity(
            UUID batchId, String recordId, String sourceId, long version, UUID lineageId) {
        return new NormalizedFactIdentity(
                batchId, recordId, sourceId, "student-status:2026-08-09", version,
                "STUDENT-1.0.0", "sha256:" + "1".repeat(64), lineageId);
    }

    private static BatchManifest manifest() {
        return new BatchManifest(
                2, 2, 0,
                new BatchObservationWindow(NOW.minusSeconds(3600), NOW),
                NOW.plusSeconds(1), "Asia/Shanghai", "sha256:" + "8".repeat(64),
                "STUDENT-1.0.0", "sha256:" + "1".repeat(64),
                "DCC-1.1.0", "sha256:" + "2".repeat(64),
                "QG-1.0.0", "sha256:" + "3".repeat(64),
                "QMDP-1.0.0", "sha256:" + "4".repeat(64),
                NOW.minusSeconds(1), NOW.plusSeconds(60), NOW,
                "student-daily", MANIFEST_DIGEST);
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
                "AUTH-2026-08-08-001", "AUTH-2026-08-08-001", NOW,
                "QSHM-1.0.0", "sha256:" + "5".repeat(64),
                "sha256:" + "6".repeat(64), "QSHM-CONTRACT-LOCK-1.0.0",
                "sha256:" + "7".repeat(64), "AUTH-2026-08-09-001",
                "AUTH-2026-08-09-001", NOW);
    }
}
