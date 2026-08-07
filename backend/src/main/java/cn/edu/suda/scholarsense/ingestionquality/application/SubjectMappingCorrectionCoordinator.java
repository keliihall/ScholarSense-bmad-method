package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;

/** Event-only composition: no synchronous callback into subject-registry. */
public final class SubjectMappingCorrectionCoordinator {
    private final SubjectMappingEventApplyPort events;
    private final MappingRecomputePlanner planner;
    private final SubjectMappingConsumerReconciliationPort reconciliation;

    public SubjectMappingCorrectionCoordinator(
            SubjectMappingEventApplyPort events,
            MappingRecomputePlanner planner,
            SubjectMappingConsumerReconciliationPort reconciliation) {
        this.events = java.util.Objects.requireNonNull(events);
        this.planner = java.util.Objects.requireNonNull(planner);
        this.reconciliation = java.util.Objects.requireNonNull(reconciliation);
    }

    public SubjectMappingCorrectionResult consume(
            SubjectMappingChangedFact fact, boolean backfill,
            Instant serverNow, String traceId) {
        SubjectMappingEventOutcome outcome = events.accept(fact, backfill, serverNow);
        MappingRecomputePlan plan = switch (outcome) {
            case APPLIED, BACKFILL_APPLIED -> planner.plan(
                    fact.eventId(), fact.correctionLineageId(), fact.sourceId(),
                    fact.affectedStudentRefs(), serverNow, traceId);
            default -> new MappingRecomputePlan(java.util.List.of(), 0);
        };
        if ((outcome == SubjectMappingEventOutcome.APPLIED
                || outcome == SubjectMappingEventOutcome.BACKFILL_APPLIED)
                && !reconciliation.reconcile(
                        fact.aggregateId(), fact.aggregateVersion(), serverNow)) {
            throw new IllegalStateException("INGESTION_QUALITY_MAPPING_RECONCILIATION_FAILED");
        }
        return new SubjectMappingCorrectionResult(outcome, plan);
    }
}
