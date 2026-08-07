package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SubjectMappingEventConsumer {
    private static final String EXPECTED_SOURCE = "urn:scholarsense:subject-registry";

    private final String consumerId;
    private final long appliesFromAggregateVersion;
    private final Map<UUID, Long> watermarks = new HashMap<>();
    private final Set<EventKey> processed = new HashSet<>();
    private final Set<UUID> gaps = new HashSet<>();
    private final Set<UUID> reconciled = new HashSet<>();
    private final Set<EventKey> quarantine = new HashSet<>();
    private boolean active;

    public SubjectMappingEventConsumer(String consumerId, long appliesFromAggregateVersion) {
        if (consumerId == null || consumerId.isBlank() || appliesFromAggregateVersion < 1) {
            throw new IllegalArgumentException("consumer contract");
        }
        this.consumerId = consumerId;
        this.appliesFromAggregateVersion = appliesFromAggregateVersion;
    }

    public SubjectMappingEventOutcome consume(SubjectMappingChangedFact fact) {
        return accept(fact, false);
    }

    public SubjectMappingEventOutcome backfill(SubjectMappingChangedFact fact) {
        SubjectMappingEventOutcome outcome = accept(fact, true);
        if (outcome == SubjectMappingEventOutcome.BACKFILL_APPLIED) {
            long expected = watermarks.getOrDefault(fact.aggregateId(), appliesFromAggregateVersion - 1) + 1;
            if (expected >= fact.aggregateVersion() + 1) {
                gaps.remove(fact.aggregateId());
            }
        }
        return outcome;
    }

    private SubjectMappingEventOutcome accept(SubjectMappingChangedFact fact, boolean backfill) {
        if (fact == null || fact.eventId() == null) {
            throw new IllegalArgumentException("event");
        }
        EventKey key = new EventKey(fact.source(), fact.eventId());
        if (processed.contains(key) || quarantine.contains(key)) {
            return SubjectMappingEventOutcome.DUPLICATE;
        }
        if (!fact.schemaValid() || !EXPECTED_SOURCE.equals(fact.source())
                || fact.aggregateId() == null || fact.aggregateVersion() < appliesFromAggregateVersion
                || fact.occurredAt() == null || fact.correctionLineageId() == null
                || fact.sourceId() == null || fact.affectedStudentRefs().isEmpty()
                || fact.inputWatermark() == null || fact.inputWatermark().isBlank()) {
            quarantine.add(key);
            active = false;
            return SubjectMappingEventOutcome.POISON_QUARANTINED;
        }
        long current = watermarks.getOrDefault(fact.aggregateId(), appliesFromAggregateVersion - 1);
        if (fact.aggregateVersion() <= current) {
            processed.add(key);
            return SubjectMappingEventOutcome.OLD_VERSION;
        }
        if (fact.aggregateVersion() != current + 1) {
            gaps.add(fact.aggregateId());
            active = false;
            return SubjectMappingEventOutcome.GAP_PAUSED;
        }
        watermarks.put(fact.aggregateId(), fact.aggregateVersion());
        processed.add(key);
        reconciled.remove(fact.aggregateId());
        if (gaps.contains(fact.aggregateId())) {
            gaps.remove(fact.aggregateId());
        }
        return backfill ? SubjectMappingEventOutcome.BACKFILL_APPLIED : SubjectMappingEventOutcome.APPLIED;
    }

    public void reconcile(UUID aggregateId, long authoritativeVersion) {
        if (aggregateId == null || authoritativeVersion < appliesFromAggregateVersion
                || watermark(aggregateId) != authoritativeVersion || gaps.contains(aggregateId)) {
            active = false;
            return;
        }
        reconciled.add(aggregateId);
    }

    public boolean activate() {
        active = !watermarks.isEmpty() && gaps.isEmpty() && quarantine.isEmpty()
                && reconciled.containsAll(watermarks.keySet());
        return active;
    }

    public long watermark(UUID aggregateId) {
        return watermarks.getOrDefault(aggregateId, appliesFromAggregateVersion - 1);
    }

    public String consumerId() { return consumerId; }
    public boolean active() { return active; }

    private record EventKey(String source, UUID eventId) {}
}
