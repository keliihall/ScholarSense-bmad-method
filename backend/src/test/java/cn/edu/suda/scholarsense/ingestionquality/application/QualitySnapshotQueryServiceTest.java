package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecisionToken;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest;
import cn.edu.suda.scholarsense.identityaccess.api.FieldVisibility;
import cn.edu.suda.scholarsense.ingestionquality.domain.BatchObservationWindow;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricBoundary;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricResultStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricUnit;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityOverallResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QualitySnapshotQueryServiceTest {
    private static final UUID SNAPSHOT_ID =
            UUID.fromString("019fe570-0000-7000-8000-000000000401");
    private static final UUID BATCH_ID =
            UUID.fromString("019fe570-0000-7000-8000-000000000402");
    private static final UUID LINEAGE_ID =
            UUID.fromString("019fe570-0000-7000-8000-000000000403");
    private static final String TRACE_ID = "00112233445566778899aabbccddeeff";
    private static final QualitySnapshotActorContext ACTOR =
            new QualitySnapshotActorContext("session-pseudonym", "actor-pseudonym", "127.0.0.1");

    @Test
    void authorizesCurrentOwnedSnapshotWithTheApprovedPurposeAndProjection() {
        QualitySnapshot snapshot = snapshot(SNAPSHOT_ID, 3);
        StubStore store = new StubStore(List.of(snapshot));
        List<CompositeAuthorizationRequest> requests = new ArrayList<>();
        List<UUID> audits = new ArrayList<>();
        var service = new QualitySnapshotQueryService(
                store,
                request -> {
                    requests.add(request);
                    return allow(request.expectedObjectVersion());
                },
                ignored -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT"),
                (viewed, actor, action, traceId) -> audits.add(viewed.snapshotId()));

        QualitySnapshotView view = service.get(SNAPSHOT_ID, ACTOR, TRACE_ID);

        assertEquals(SNAPSHOT_ID, view.snapshotId());
        assertEquals("quality-passed", view.assessedBatchStatus());
        assertEquals("passed", view.metricResults().getFirst().result());
        assertEquals(List.of(SNAPSHOT_ID), audits);
        CompositeAuthorizationRequest request = requests.getFirst();
        assertEquals("session-pseudonym", request.actorPseudonym());
        assertEquals("QUALITY_SNAPSHOT", request.objectClass());
        assertEquals("data-quality.read", request.actionId());
        assertEquals(3, request.expectedObjectVersion());
        assertEquals(64, request.objectTokenDigest().length());
    }

    @Test
    void missingAndDeniedDetailsAreIndistinguishableAndNeverAudited() {
        List<UUID> audits = new ArrayList<>();
        var missing = service(new StubStore(List.of()), ignored -> deny(), audits);
        var denied = service(new StubStore(List.of(snapshot(SNAPSHOT_ID, 3))), ignored -> deny(), audits);

        assertEquals("INGESTION_QUALITY_FORBIDDEN", code(() -> missing.get(SNAPSHOT_ID, ACTOR, TRACE_ID)));
        assertEquals("INGESTION_QUALITY_FORBIDDEN", code(() -> denied.get(SNAPSHOT_ID, ACTOR, TRACE_ID)));
        assertTrue(audits.isEmpty());
    }

    @Test
    void listOmitsDeniedRowsAndDoesNotWriteSensitiveDrilldownAudit() {
        UUID deniedId = UUID.fromString("019fe570-0000-7000-8000-000000000404");
        List<UUID> audits = new ArrayList<>();
        var service = service(
                new StubStore(List.of(snapshot(SNAPSHOT_ID, 3), snapshot(deniedId, 3))),
                request -> request.objectTokenDigest().equals(QualitySnapshotQueryService.digest(SNAPSHOT_ID))
                        ? allow(3) : deny(), audits);

        List<QualitySnapshotView> views = service.list(
                new QualitySnapshotQueryCriteria(null, null, null, null, null, null, 21),
                ACTOR, TRACE_ID);

        assertEquals(List.of(SNAPSHOT_ID), views.stream().map(QualitySnapshotView::snapshotId).toList());
        assertTrue(audits.isEmpty());
    }

    @Test
    void listRechecksAuthorizationWithoutPerCandidateDetailHydration() {
        UUID secondId = UUID.fromString("019fe570-0000-7000-8000-000000000405");
        StubStore store = new StubStore(List.of(
                snapshot(SNAPSHOT_ID, 3), snapshot(secondId, 3)));
        var service = service(store, ignored -> allow(3), new ArrayList<>());

        List<QualitySnapshotView> views = service.list(
                new QualitySnapshotQueryCriteria(null, null, null, null, null, null, 21),
                ACTOR, TRACE_ID);

        assertEquals(List.of(SNAPSHOT_ID, secondId),
                views.stream().map(QualitySnapshotView::snapshotId).toList());
        assertEquals(0, store.detailReads,
                "the bulk-hydrated immutable page must not fall back to N detail queries");
    }

    @Test
    void listContinuesByRawKeysetUntilTheAuthorizedPageIsFull() {
        List<UUID> deniedIds = java.util.stream.IntStream.rangeClosed(480, 499)
                .mapToObj(QualitySnapshotQueryServiceTest::snapshotId).toList().reversed();
        UUID firstAllowedId = snapshotId(479);
        UUID secondAllowedId = snapshotId(478);
        List<QualitySnapshot> rows = new ArrayList<>();
        deniedIds.forEach(id -> rows.add(snapshot(id, 3)));
        rows.add(snapshot(firstAllowedId, 3));
        rows.add(snapshot(secondAllowedId, 3));
        PagedStore store = new PagedStore(rows);
        var service = service(
                store,
                request -> deniedIds.stream().map(QualitySnapshotQueryService::digest)
                        .anyMatch(request.objectTokenDigest()::equals) ? deny() : allow(3),
                new ArrayList<>());

        List<QualitySnapshotView> views = service.list(
                new QualitySnapshotQueryCriteria(null, null, null, null, null, null, 2),
                ACTOR, TRACE_ID);

        assertEquals(List.of(firstAllowedId, secondAllowedId),
                views.stream().map(QualitySnapshotView::snapshotId).toList());
        assertEquals(2, store.listCalls);
        assertEquals(firstAllowedId, store.afterCursors.get(1));
    }

    @Test
    void staleSerializationRecheckOrChangedSnapshotFailsClosed() {
        QualitySnapshot snapshot = snapshot(SNAPSHOT_ID, 3);
        StubStore store = new StubStore(List.of(snapshot));
        var stale = new QualitySnapshotQueryService(
                store, ignored -> allow(3),
                ignored -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.STALE, "STALE"),
                (viewed, actor, action, traceId) -> {});
        assertEquals("INGESTION_QUALITY_FORBIDDEN", code(() -> stale.get(SNAPSHOT_ID, ACTOR, TRACE_ID)));

        store.detail = Optional.of(snapshot(SNAPSHOT_ID, 4));
        var changed = service(store, ignored -> allow(3), new ArrayList<>());
        assertEquals("INGESTION_QUALITY_FORBIDDEN", code(() -> changed.get(SNAPSHOT_ID, ACTOR, TRACE_ID)));
    }

    @Test
    void dependencyAndReadAuditFailureRemainUnavailable() {
        StubStore store = new StubStore(List.of(snapshot(SNAPSHOT_ID, 3)));
        var dependency = service(store, ignored -> unavailable(), new ArrayList<>());
        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE",
                code(() -> dependency.get(SNAPSHOT_ID, ACTOR, TRACE_ID)));

        var auditFailure = new QualitySnapshotQueryService(
                store, ignored -> allow(3),
                ignored -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT"),
                (viewed, actor, action, traceId) -> { throw new IllegalStateException("audit down"); });
        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE",
                code(() -> auditFailure.get(SNAPSHOT_ID, ACTOR, TRACE_ID)));
    }

    @Test
    void metricDrilldownUsesMetricFormulaAndVersionAsItsStableIdentity() {
        QualityMetricResult common = metric(
                "QMDP-1.0.0/primary-key-completeness", "1.0.0");
        QualityMetricResult sourceGate = metric(
                "QMDP-1.0.0/SRC-P0-STUDENT-001/primary-key-completeness", "1.0.0");
        QualitySnapshot snapshot = snapshot(SNAPSHOT_ID, 3, List.of(common, sourceGate));
        var service = service(new StubStore(List.of(snapshot)), ignored -> allow(3),
                new ArrayList<>());

        QualitySnapshotMetricView result = service.getMetric(
                SNAPSHOT_ID, "PRIMARY_KEY_COMPLETENESS",
                sourceGate.formulaId(), sourceGate.formulaVersion(), ACTOR, TRACE_ID);

        assertEquals(sourceGate.formulaId(), result.formulaId());
        assertEquals(sourceGate.formulaVersion(), result.formulaVersion());
    }

    private static QualitySnapshotQueryService service(
            QualitySnapshotQueryPort store,
            cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort authorization,
            List<UUID> audits) {
        return new QualitySnapshotQueryService(
                store, authorization,
                ignored -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT"),
                (viewed, actor, action, traceId) -> audits.add(viewed.snapshotId()));
    }

    private static String code(Runnable invocation) {
        return assertThrows(IngestionQualityApplicationException.class, invocation::run).code();
    }

    private static CompositeAuthorizationDecision allow(long version) {
        return decision(CompositeAuthorizationOutcome.ALLOW, "ALLOW", version, Map.of(
                "B", FieldVisibility.CLEAR,
                "I", FieldVisibility.HIDDEN,
                "C", FieldVisibility.HIDDEN,
                "S", FieldVisibility.HIDDEN,
                "E", FieldVisibility.CLEAR,
                "N", FieldVisibility.HIDDEN,
                "G", FieldVisibility.CLEAR,
                "T", FieldVisibility.CLEAR));
    }

    private static CompositeAuthorizationDecision deny() {
        return decision(CompositeAuthorizationOutcome.DENY, "DENY", 3, Map.of());
    }

    private static CompositeAuthorizationDecision unavailable() {
        return decision(CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE,
                "DEPENDENCY_UNAVAILABLE", 3, Map.of());
    }

    private static CompositeAuthorizationDecision decision(
            CompositeAuthorizationOutcome outcome,
            String reason,
            long version,
            Map<String, FieldVisibility> fields) {
        return new CompositeAuthorizationDecision(
                outcome, reason, Set.of("R6-DATA-OWNER"), Set.of("OWNED_SOURCE"), fields,
                Set.of(), "RFP-1.0.0", version, Instant.parse("2026-08-10T00:00:00Z"),
                new CompositeAuthorizationDecisionToken(1, 1, 0, 0, 1, version, "RFP-1.0.0"));
    }

    private static QualitySnapshot snapshot(UUID snapshotId, long version) {
        return snapshot(snapshotId, version, List.of(metric(
                "QMDP-1.0.0/primary-key-completeness", "1.0.0")));
    }

    private static QualityMetricResult metric(String formulaId, String formulaVersion) {
        return new QualityMetricResult(
                "PRIMARY_KEY_COMPLETENESS", formulaId, formulaVersion,
                QualityMetricResultStatus.PASSED, true,
                BigInteger.valueOf(100), BigInteger.valueOf(100),
                BigInteger.valueOf(10000), QualityMetricUnit.BASIS_POINT,
                QualityMetricOperator.GREATER_THAN_OR_EQUAL,
                BigInteger.valueOf(995), BigInteger.valueOf(1000),
                QualityMetricBoundary.INCLUSIVE, null);
    }

    private static QualitySnapshot snapshot(
            UUID snapshotId, long version, List<QualityMetricResult> metrics) {
        Instant start = Instant.parse("2026-08-09T00:00:00Z");
        Instant end = Instant.parse("2026-08-10T00:00:00Z");
        return new QualitySnapshot(
                "scholarsense.ingestion-quality.quality-snapshot.immutable-hash.v1",
                "QSHM-1.0.0", "sha256:" + "1".repeat(64), BATCH_ID,
                "SRC-P0-STUDENT-001", DataBatchStatus.QUALITY_PASSED,
                QualityOverallResult.QUALITY_PASSED, new BatchObservationWindow(start, end), end,
                "src-p0-student-001@2026-08-10",
                metrics,
                List.of("PRIMARY_KEY_COMPLETENESS"), "SRC-P0-STUDENT-001",
                "AUTH-2026-08-08-001", start, "RS-1.0.0", "QMDP-1.0.0",
                "sha256:" + "2".repeat(64), "QG-1.0.0", "sha256:" + "3".repeat(64),
                "SCHOLARSENSE-CANONICAL-JSON-1.0.0", "sha256:" + "4".repeat(64),
                "SIS-1.0.0", "sha256:" + "5".repeat(64), LINEAGE_ID, null,
                snapshotId, end, TRACE_ID, version, "sha256:" + "6".repeat(64));
    }

    private static UUID snapshotId(int suffix) {
        return UUID.fromString("019fe570-0000-7000-8000-" + String.format("%012d", suffix));
    }

    private static final class StubStore implements QualitySnapshotQueryPort {
        private final List<QualitySnapshot> snapshots;
        private Optional<QualitySnapshot> detail;
        private int detailReads;

        private StubStore(List<QualitySnapshot> snapshots) {
            this.snapshots = List.copyOf(snapshots);
            this.detail = snapshots.isEmpty() ? Optional.empty() : Optional.of(snapshots.getFirst());
        }

        @Override
        public List<QualitySnapshot> findAssessed(QualitySnapshotQueryCriteria criteria) {
            return snapshots;
        }

        @Override
        public Optional<QualitySnapshot> findById(UUID snapshotId) {
            detailReads++;
            return detail.filter(snapshot -> snapshot.snapshotId().equals(snapshotId));
        }
    }

    private static final class PagedStore implements QualitySnapshotQueryPort {
        private final List<QualitySnapshot> snapshots;
        private final List<UUID> afterCursors = new ArrayList<>();
        private int listCalls;

        private PagedStore(List<QualitySnapshot> snapshots) {
            this.snapshots = List.copyOf(snapshots);
        }

        @Override
        public List<QualitySnapshot> findAssessed(QualitySnapshotQueryCriteria criteria) {
            listCalls++;
            afterCursors.add(criteria.afterSnapshotId());
            int start = criteria.afterSnapshotId() == null
                    ? 0
                    : snapshots.stream().map(QualitySnapshot::snapshotId).toList()
                            .indexOf(criteria.afterSnapshotId()) + 1;
            return snapshots.stream().skip(start).limit(criteria.limit()).toList();
        }

        @Override
        public Optional<QualitySnapshot> findById(UUID snapshotId) {
            return snapshots.stream().filter(value -> value.snapshotId().equals(snapshotId)).findFirst();
        }
    }
}
