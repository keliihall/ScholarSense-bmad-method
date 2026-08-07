package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubjectMappingEventConsumerTest {

    private static final UUID AGGREGATE = uuid("019fcfea-6300-7000-8000-000000000001");
    private static final UUID LINEAGE = uuid("019fcfea-6300-7000-8000-000000000002");
    private static final String SUBJECT = "019fcfea-6300-7000-8000-000000000003";
    private static final Instant NOW = Instant.parse("2026-08-06T08:00:00Z");

    @Test
    void duplicateOldGapBackfillAndActivationAreExplicitAndMonotonic() {
        SubjectMappingEventConsumer consumer = new SubjectMappingEventConsumer(
                "ingestion-quality-subject-window", 1);
        SubjectMappingChangedFact first = fact(
                "019fcfea-6300-7000-8000-000000000010", 1, true);

        assertEquals(SubjectMappingEventOutcome.APPLIED, consumer.consume(first));
        assertEquals(SubjectMappingEventOutcome.DUPLICATE, consumer.consume(first));
        assertEquals(SubjectMappingEventOutcome.OLD_VERSION, consumer.consume(fact(
                "019fcfea-6300-7000-8000-000000000011", 1, true)));
        assertEquals(SubjectMappingEventOutcome.GAP_PAUSED, consumer.consume(fact(
                "019fcfea-6300-7000-8000-000000000013", 3, true)));
        assertFalse(consumer.activate());
        assertEquals(SubjectMappingEventOutcome.BACKFILL_APPLIED, consumer.backfill(fact(
                "019fcfea-6300-7000-8000-000000000012", 2, true)));
        assertEquals(SubjectMappingEventOutcome.APPLIED, consumer.consume(fact(
                "019fcfea-6300-7000-8000-000000000013", 3, true)));
        assertFalse(consumer.activate());
        consumer.reconcile(AGGREGATE, 3);
        assertTrue(consumer.activate());
        assertEquals(3, consumer.watermark(AGGREGATE));
    }

    @Test
    void poisonIsQuarantinedAndPlannedConsumersNeverCountAsRuntimeCompletion() {
        SubjectMappingEventConsumer consumer = new SubjectMappingEventConsumer(
                "ingestion-quality-subject-window", 1);
        assertEquals(SubjectMappingEventOutcome.POISON_QUARANTINED, consumer.consume(fact(
                "019fcfea-6300-7000-8000-000000000014", 1, false)));
        assertFalse(consumer.activate());

        SubjectMappingConsumerRegistry registry = new SubjectMappingConsumerRegistry();
        registry.registerActive("ingestion-quality-subject-window", 3, true);
        registry.registerPlanned("signal-evaluation-supersede", "runtimeEvidenceClaim=none");
        registry.registerPlanned("clue-care-correction-review", "runtimeEvidenceClaim=none");
        assertTrue(registry.correctionCompleteAt(3));
        assertFalse(registry.correctionCompleteAt(4));
    }

    private static SubjectMappingChangedFact fact(String id, long version, boolean schemaValid) {
        return new SubjectMappingChangedFact(
                "urn:scholarsense:subject-registry", uuid(id), AGGREGATE, version,
                NOW.plusSeconds(version), LINEAGE, "SRC-P0-CARD-001", Set.of(SUBJECT),
                "wm-" + version, schemaValid);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
