package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecisionToken;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.FieldVisibility;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleVersionIdentity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QualityRecoveryTaskQueryServiceTest {
    private static final UUID TASK_ID = UUID.fromString(
            "019fe8a0-0000-7000-8000-000000000801");
    private static final String TRACE = "11111111111111111111111111111111";
    private static final QualitySnapshotActorContext ACTOR =
            new QualitySnapshotActorContext("session-ref", "actor-ref", "127.0.0.1");

    @Test
    void authorizesOwnedSourceRechecksAndAuditsBeforeReturningDeliverySidecar() {
        List<UUID> audited = new ArrayList<>();
        var service = new QualityRecoveryTaskQueryService(
                new Store(task()), request -> allow(request.expectedObjectVersion()),
                ignored -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT"),
                (tasks, actor, action, trace) ->
                        tasks.forEach(task -> audited.add(task.taskId())));

        QualityRecoveryTaskView view = service.get(TASK_ID, ACTOR, TRACE);

        assertEquals(TASK_ID, view.taskId());
        assertEquals("pending", view.taskDelivery().status());
        assertEquals(List.of(TASK_ID), audited);
    }

    @Test
    void unownedListRowsAreOmittedAndDetailIsConcealedWithoutAudit() {
        List<UUID> audited = new ArrayList<>();
        Store store = new Store(task());
        var service = new QualityRecoveryTaskQueryService(
                store, request -> deny(request.expectedObjectVersion()),
                ignored -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT"),
                (tasks, actor, action, trace) ->
                        tasks.forEach(task -> audited.add(task.taskId())));

        assertEquals(List.of(), service.list(
                new QualityRecoveryTaskQueryCriteria(
                        "SRC-P0-CAMPUS-ACCESS-001", null, null, null, 21),
                ACTOR, TRACE));
        assertEquals(0, store.listReads,
                "an unowned source must be denied before any page slice is read");
        assertEquals("INGESTION_QUALITY_FORBIDDEN", code(() ->
                service.get(TASK_ID, ACTOR, TRACE)));
        assertEquals(List.of(), audited);
    }

    @Test
    void ownedSourceIsAppliedBeforeTheBoundedPageSlice() {
        Store store = new Store(task());
        var service = new QualityRecoveryTaskQueryService(
                store, request -> allow(request.expectedObjectVersion()),
                ignored -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT"),
                (tasks, actor, action, trace) -> {});

        List<QualityRecoveryTaskView> visible = service.list(
                new QualityRecoveryTaskQueryCriteria(
                        "SRC-P0-CAMPUS-ACCESS-001", "open", null, null, 21),
                ACTOR, TRACE);

        assertEquals(List.of(TASK_ID), visible.stream()
                .map(QualityRecoveryTaskView::taskId).toList());
        assertEquals(1, store.listReads);
        assertEquals("SRC-P0-CAMPUS-ACCESS-001", store.lastCriteria.sourceId());
        assertEquals(21, store.lastCriteria.limit(),
                "the authorized owner slice must not be replaced with a global 101-row scan");
    }

    @Test
    void staleAuthorizationOrFactSubstitutionFailsClosed() {
        var stale = new QualityRecoveryTaskQueryService(
                new Store(task()), request -> allow(request.expectedObjectVersion()),
                ignored -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.STALE, "STALE"),
                (tasks, actor, action, trace) -> {});
        assertEquals("INGESTION_QUALITY_FORBIDDEN", code(() ->
                stale.get(TASK_ID, ACTOR, TRACE)));

        var substituted = new QualityRecoveryTaskQueryService(
                new Store(task(), task(2)), request -> allow(request.expectedObjectVersion()),
                ignored -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT"),
                (tasks, actor, action, trace) -> {});
        assertEquals("INGESTION_QUALITY_FORBIDDEN", code(() ->
                substituted.get(TASK_ID, ACTOR, TRACE)));
    }

    private static String code(Runnable action) {
        return assertThrows(IngestionQualityApplicationException.class, action::run).code();
    }

    private static CompositeAuthorizationDecision allow(long version) {
        return decision(CompositeAuthorizationOutcome.ALLOW, version, Map.of(
                "B", FieldVisibility.CLEAR, "I", FieldVisibility.MASKED,
                "C", FieldVisibility.HIDDEN, "S", FieldVisibility.HIDDEN,
                "E", FieldVisibility.CLEAR, "N", FieldVisibility.HIDDEN,
                "G", FieldVisibility.CLEAR, "T", FieldVisibility.CLEAR));
    }

    private static CompositeAuthorizationDecision deny(long version) {
        return decision(CompositeAuthorizationOutcome.DENY, version, Map.of());
    }

    private static CompositeAuthorizationDecision decision(
            CompositeAuthorizationOutcome outcome,
            long version,
            Map<String, FieldVisibility> fields) {
        return new CompositeAuthorizationDecision(
                outcome, outcome.name(), Set.of("R6-DATA-OWNER"), Set.of("OWNED_SOURCE"),
                fields, Set.of(), "RFP-1.0.0", version,
                Instant.parse("2026-08-10T00:00:00Z"),
                new CompositeAuthorizationDecisionToken(
                        1, 1, 1, 1, 1, version, "RFP-1.0.0"));
    }

    private static QualityRecoveryTask task() {
        return task(1);
    }

    private static QualityRecoveryTask task(long version) {
        return new QualityRecoveryTask(
                TASK_ID,
                UUID.fromString("019fe8a0-0000-7000-8000-000000000802"),
                1, "SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001",
                List.of(new RuleVersionIdentity("ACC-SAFE-001", "1.0.0")),
                "source-owner:SRC-P0-CAMPUS-ACCESS-001", "P1",
                Instant.parse("2026-08-11T00:00:00Z"), "open", "opaque-watermark",
                Map.of("reasonCode", "REQUIRED_MEMBER_FUSED"),
                Map.of("qmdpVersion", "QMDP-1.0.0"), version,
                Instant.parse("2026-08-10T00:00:00Z"),
                new QualityTaskDeliveryProjection(
                        "public-task-platform", "pending", 0, null, null),
                TRACE);
    }

    private static final class Store implements QualityRecoveryTaskQueryPort {
        private final QualityRecoveryTask first;
        private final QualityRecoveryTask second;
        private int reads;
        private int listReads;
        private QualityRecoveryTaskQueryCriteria lastCriteria;

        private Store(QualityRecoveryTask value) {
            this(value, value);
        }

        private Store(QualityRecoveryTask first, QualityRecoveryTask second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public List<QualityRecoveryTask> findCurrent(QualityRecoveryTaskQueryCriteria criteria) {
            listReads++;
            lastCriteria = criteria;
            return List.of(first);
        }

        @Override
        public Optional<QualityRecoveryTask> findCurrentById(UUID taskId) {
            return Optional.of(reads++ == 0 ? first : second);
        }
    }
}
