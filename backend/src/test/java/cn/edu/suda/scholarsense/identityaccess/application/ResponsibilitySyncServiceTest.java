package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationChangeKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReason;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientEvidence;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientValidity;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStudentSourceReference;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityType;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResponsibilitySyncServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "responsibility-authority",
            "sandbox-0",
            "responsibility");
    private static final CheckpointKey IDENTITY_KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "identity-authority",
            "sandbox-0",
            "identity-org");
    private static final IdentityLease LEASE = new IdentityLease(
            KEY,
            UUID.fromString("019c1234-0000-7000-8000-000000000301"),
            1,
            9,
            "responsibility-worker-a",
            NOW.minusSeconds(1),
            NOW.plus(Duration.ofMinutes(2)));

    @Test
    void appliesContinuousResponsibilityWithoutAdvancingIdentityCheckpoint() {
        var store = new FakeRepository();
        store.identityWatermark = 42;
        var audits = new ArrayList<IdentitySyncAuditEvent>();
        var service = service(store, (key, from, to, trace) -> {}, audits);

        IdentitySyncResult result = service.process(batch(6, 7), LEASE);

        assertEquals(IdentitySyncOutcome.APPLIED, result.outcome());
        assertEquals(7, result.sourceWatermark());
        assertTrue(store.applied);
        assertEquals(42, store.identityWatermark);
        assertEquals("responsibility.sync.applied", audits.getFirst().action());
    }

    @Test
    void v2ReplaysFromZeroAgainstShadowAndAuditsActualContractWithoutPublishing() {
        var store = new FakeRepository();
        store.v2Checkpoint = Optional.empty();
        var audits = new ArrayList<IdentitySyncAuditEvent>();
        var published = new ArrayList<CommittedResponsibilityChangeSet>();
        var service = service(store, (key, from, to, trace) -> {}, audits,
                published);

        IdentitySyncResult result = service.process(v2Batch(0, 1), LEASE);

        assertEquals(IdentitySyncOutcome.APPLIED, result.outcome());
        assertTrue(store.v2Applied);
        assertFalse(store.applied);
        assertTrue(published.isEmpty());
        assertEquals(
                "RESPONSIBILITY-AUTHORITY-2.0.0",
                audits.getFirst().policyVersions()
                        .get("responsibilityContract"));
    }

    @Test
    void v2CannotSkipZeroOrRebindStableRelationLineage() {
        var store = new FakeRepository();
        store.v2Checkpoint = Optional.empty();
        var ranges = new ArrayList<String>();
        var service = service(
                store,
                (key, from, to, trace) -> ranges.add(from + ":" + to),
                new ArrayList<>());

        IdentitySyncException gap = assertThrows(
                IdentitySyncException.class,
                () -> service.process(v2Batch(1, 2), LEASE));
        assertEquals("RESPONSIBILITY_SOURCE_VERSION_GAP", gap.code());
        assertTrue(ranges.isEmpty());

        store.boundLineage = Optional.of(new AccessInvalidationLineageId(
                "lin_" + "9".repeat(40)));
        IdentitySyncException rebind = assertThrows(
                IdentitySyncException.class,
                () -> service.process(v2Batch(0, 1), LEASE));
        assertEquals("RESPONSIBILITY_IDEMPOTENCY_CONFLICT", rebind.code());
        assertFalse(store.v2Applied);
    }

    @Test
    void firstV2DataBatchMustStartAtSourceVersionOne() {
        var store = new FakeRepository();
        store.v2Checkpoint = Optional.empty();

        IdentitySyncException gap = assertThrows(
                IdentitySyncException.class,
                () -> service(
                                store,
                                (key, from, to, trace) -> {},
                                new ArrayList<>())
                        .process(v2Batch(0, 2), LEASE));

        assertEquals("RESPONSIBILITY_SOURCE_VERSION_GAP", gap.code());
        assertFalse(store.v2Applied);
        assertEquals(1, store.rejections.size());
        assertEquals(
                "RESPONSIBILITY_SOURCE_VERSION_GAP",
                store.rejections.getFirst().reasonCode());
        assertTrue(store.rejections.getFirst().replayable());
    }

    @Test
    void v2DataBatchCannotSkipTheCheckpointSourceVersion() {
        var store = new FakeRepository();
        store.v2Checkpoint = Optional.of(new IdentityCheckpoint(
                KEY,
                1,
                1,
                1,
                NOW.minusSeconds(10),
                IdentitySourceHealth.HEALTHY,
                IdentityProjectionFreshness.FRESH));

        IdentitySyncException gap = assertThrows(
                IdentitySyncException.class,
                () -> service(
                                store,
                                (key, from, to, trace) -> {},
                                new ArrayList<>())
                        .process(v2Batch(1, 3), LEASE));

        assertEquals("RESPONSIBILITY_SOURCE_VERSION_GAP", gap.code());
        assertFalse(store.v2Applied);
        assertEquals(1, store.rejections.size());
        assertEquals(
                "RESPONSIBILITY_SOURCE_VERSION_GAP",
                store.rejections.getFirst().reasonCode());
        assertTrue(store.rejections.getFirst().replayable());
    }

    @Test
    void activeV2ShadowRejectsBothV1DataAndHeartbeatWithoutSideEffects() {
        var store = new FakeRepository();
        store.v2Active = true;
        var published = new ArrayList<CommittedResponsibilityChangeSet>();
        var service = service(
                store,
                (key, from, to, trace) -> {},
                new ArrayList<>(),
                published);

        IdentitySyncException dataRejected = assertThrows(
                IdentitySyncException.class,
                () -> service.process(batch(6, 7), LEASE));
        IdentitySyncException heartbeatRejected = assertThrows(
                IdentitySyncException.class,
                () -> service.process(heartbeat(), LEASE));

        assertEquals(
                "RESPONSIBILITY_V2_REPLAY_REQUIRED",
                dataRejected.code());
        assertEquals(
                "RESPONSIBILITY_V2_REPLAY_REQUIRED",
                heartbeatRejected.code());
        assertFalse(store.applied);
        assertFalse(store.v2Applied);
        assertFalse(store.heartbeatRecorded);
        assertTrue(published.isEmpty());
        assertEquals(
                List.of(
                        "RESPONSIBILITY_V2_REPLAY_REQUIRED",
                        "RESPONSIBILITY_V2_REPLAY_REQUIRED"),
                store.rejections.stream()
                        .map(IdentitySyncRejection::reasonCode)
                        .toList());
    }

    @Test
    void supportingIdentityWatermarkBehindFailsClosedWithoutFakeExceptionOrApply() {
        var store = new FakeRepository();
        store.identityWatermark = 41;
        var service = service(store, (key, from, to, trace) -> {}, new ArrayList<>());

        IdentitySyncException failure = assertThrows(
                IdentitySyncException.class,
                () -> service.process(batch(6, 7), LEASE));

        assertEquals("RESPONSIBILITY_DEPENDENCY_WATERMARK_BEHIND", failure.code());
        assertFalse(store.applied);
        assertEquals(0, store.exceptionWrites);
    }

    @Test
    void gapRequestsOnlyResponsibilityReplayAndSameKeyDifferentPayloadConflicts() {
        var store = new FakeRepository();
        store.checkpoint = Optional.of(new IdentityCheckpoint(
                KEY,
                4,
                4,
                4,
                NOW.minusSeconds(10),
                IdentitySourceHealth.HEALTHY,
                IdentityProjectionFreshness.FRESH));
        var ranges = new ArrayList<String>();
        var service = service(
                store,
                (key, from, to, trace) ->
                        ranges.add(key.consumerProjection() + ":" + from + ":" + to),
                new ArrayList<>());

        IdentitySyncException gap = assertThrows(
                IdentitySyncException.class,
                () -> service.process(batch(6, 7), LEASE));
        assertEquals("RESPONSIBILITY_WATERMARK_GAP", gap.code());
        assertEquals(List.of("responsibility:5:6"), ranges);

        store.checkpoint = Optional.of(new IdentityCheckpoint(
                KEY,
                6,
                6,
                6,
                NOW.minusSeconds(10),
                IdentitySourceHealth.HEALTHY,
                IdentityProjectionFreshness.FRESH));
        store.current = Optional.of(
                new ResponsibilityRecordState(7, "f".repeat(64)));
        IdentitySyncException conflict = assertThrows(
                IdentitySyncException.class,
                () -> service.process(batch(6, 7), LEASE));
        assertEquals("RESPONSIBILITY_IDEMPOTENCY_CONFLICT", conflict.code());
        assertFalse(store.applied);
    }

    @Test
    void staleFencingTokenProducesZeroWrites() {
        var store = new FakeRepository();
        store.leaseCurrent = false;

        IdentitySyncException failure = assertThrows(
                IdentitySyncException.class,
                () -> service(store, (key, from, to, trace) -> {}, new ArrayList<>())
                        .process(batch(6, 7), LEASE));

        assertEquals("IDENTITY_SYNC_FENCING_STALE", failure.code());
        assertFalse(store.applied);
        assertTrue(store.rejections.isEmpty());
    }

    @Test
    void authenticatedHeartbeatKeepsIndependentCheckpointAndCompletesJobCallback() {
        var store = new FakeRepository();
        var audits = new ArrayList<IdentitySyncAuditEvent>();
        var callback = new ArrayList<IdentitySyncResult>();

        IdentitySyncResult result = service(
                store, (key, from, to, trace) -> {}, audits)
                .process(heartbeat(), LEASE, callback::add);

        assertEquals(IdentitySyncOutcome.REPLAYED, result.outcome());
        assertEquals("RESPONSIBILITY_SYNC_HEARTBEAT", result.reasonCode());
        assertEquals(6, result.sourceWatermark());
        assertFalse(store.applied);
        assertEquals(List.of(result), callback);
        assertEquals(
                "responsibility.sync.heartbeat",
                audits.getFirst().action());
        assertTrue(store.heartbeatRecorded);

        IdentitySyncResult replayed = service(
                store, (key, from, to, trace) -> {}, audits)
                .process(heartbeat(), LEASE);
        assertEquals(IdentitySyncOutcome.REPLAYED, replayed.outcome());
        assertEquals(1, audits.size());
    }

    @Test
    void overlappingPrimaryIntervalsAreRejectedBeforeCheckpointAdvance() {
        var store = new FakeRepository();
        var first = batch(6, 7).relations().getFirst();
        var second = new AuthoritativeResponsibilityRelation(
                UUID.fromString(
                        "019c1234-0000-7000-8000-000000000309"),
                first.sourceId(),
                "rtok_" + "9".repeat(40),
                first.studentSourceReference(),
                first.counselorAccountRefDigest(),
                first.collegeOrganizationRefDigest(),
                ResponsibilityType.PRIMARY,
                ResponsibilityStatus.ACTIVE,
                new EffectiveInterval(
                        NOW.minusSeconds(30), NOW.plusSeconds(60)),
                first.sourceVersion(),
                first.sourceWatermark(),
                first.recordVersion(),
                first.aggregateVersion(),
                "9".repeat(64));
        NormalizedResponsibilityBatch overlapping =
                withRelations(batch(6, 7), List.of(first, second));

        IdentitySyncException failure = assertThrows(
                IdentitySyncException.class,
                () -> service(
                                store,
                                (key, from, to, trace) -> {},
                                new ArrayList<>())
                        .process(overlapping, LEASE));

        assertEquals(
                "RESPONSIBILITY_OVERLAPPING_PRIMARY",
                failure.code());
        assertFalse(store.applied);
        assertEquals(
                "RESPONSIBILITY_OVERLAPPING_PRIMARY",
                store.rejections.getFirst().reasonCode());
    }

    @Test
    void recipientEvidenceIsPersistedWithTheSameScopeTransaction() {
        var store = new FakeRepository();
        var service = new ResponsibilitySyncService(
                store,
                (relations, now) -> relations.stream()
                        .map(relation -> new ResponsibilityRecipientEvidence(
                                relation,
                                UUID.fromString(
                                        "019c1234-0000-7000-8000-000000000305"),
                                UUID.fromString(
                                        "019c1234-0000-7000-8000-000000000306"),
                                true,
                                true,
                                true,
                                true))
                        .toList(),
                (key, from, to, trace) -> {},
                new IdentitySyncTransactionPort() {
                    @Override
                    public <T> T execute(
                            java.util.function.Supplier<T> work) {
                        return work.get();
                    }
                },
                ignored -> {},
                ignored -> {},
                ResponsibilitySyncServiceTest::trustedNow);

        service.process(batch(6, 7), LEASE);

        assertEquals(1, store.scopeUpdates.size());
        assertEquals(
                ResponsibilityRecipientValidity.VALID,
                store.scopeUpdates.getFirst().decision().validity());
    }

    @Test
    void realExceptionTransitionIsAuditedWithTheCoreCommit() {
        var store = new FakeRepository();
        UUID exceptionId = UUID.fromString(
                "019c1234-0000-7000-8000-000000000307");
        store.transitions = List.of(
                new ResponsibilityExceptionAuditTransition(
                        exceptionId,
                        "responsibility.exception.opened",
                        "RESPONSIBILITY_ZERO_RECIPIENT",
                        1));
        var audits = new ArrayList<IdentitySyncAuditEvent>();

        service(store, (key, from, to, trace) -> {}, audits)
                .process(batch(6, 7), LEASE);

        assertEquals(
                List.of(
                        "responsibility.sync.applied",
                        "responsibility.exception.opened"),
                audits.stream()
                        .map(IdentitySyncAuditEvent::action)
                        .toList());
        assertEquals(
                exceptionId,
                audits.get(1).auditedObjectId());
        assertEquals(9, audits.get(1).fencingToken());
        assertEquals(7, audits.get(1).sourceWatermark());
    }

    @Test
    void publishesOnlyForActivatedV2NotLiveV1ShadowReplayOrHeartbeat() {
        var store = new FakeRepository();
        var published = new ArrayList<CommittedResponsibilityChangeSet>();
        AccessInvalidationChangePublisherPort invalidations =
                new AccessInvalidationChangePublisherPort() {
                    @Override
                    public void publish(CommittedIdentityChangeSet changeSet) {}

                    @Override
                    public void publish(
                            CommittedResponsibilityChangeSet changeSet) {
                        published.add(changeSet);
                    }
                };
        var service = new ResponsibilitySyncService(
                store,
                (relations, now) -> relations.stream()
                        .map(relation -> new ResponsibilityRecipientEvidence(
                                relation,
                                null,
                                null,
                                false,
                                false,
                                false,
                                false))
                        .toList(),
                (key, from, to, trace) -> {},
                new IdentitySyncTransactionPort() {
                    @Override
                    public <T> T execute(
                            java.util.function.Supplier<T> work) {
                        return work.get();
                    }
                },
                ignored -> {},
                ignored -> {},
                ResponsibilitySyncServiceTest::trustedNow,
                invalidations);

        NormalizedResponsibilityBatch liveV1 = batch(6, 7);
        service.process(liveV1, LEASE);
        assertTrue(published.isEmpty());

        store.v2Checkpoint = Optional.of(new IdentityCheckpoint(
                KEY,
                6,
                6,
                6,
                NOW.minusSeconds(10),
                IdentitySourceHealth.HEALTHY,
                IdentityProjectionFreshness.FRESH));
        store.v2Active = true;
        service.process(v2Batch(6, 7), LEASE);
        assertEquals(1, published.size());
        assertEquals(
                IdentitySyncOutcome.APPLIED,
                published.getFirst().result().outcome());
        assertEquals(
                "RESPONSIBILITY-AUTHORITY-2.0.0",
                published.getFirst().batch().contractVersion());

        store.heartbeatBatchId = liveV1.batchId();
        store.heartbeatEnvelopeDigest = liveV1.envelopeDigest();
        IdentitySyncException staleV1 = assertThrows(
                IdentitySyncException.class,
                () -> service.process(liveV1, LEASE));
        assertEquals(
                "RESPONSIBILITY_V2_REPLAY_REQUIRED",
                staleV1.code());
        service.process(v2Heartbeat(), LEASE);
        assertTrue(store.v2HeartbeatRecorded);
        assertEquals(1, published.size());
    }

    private static ResponsibilitySyncService service(
            FakeRepository repository,
            IdentityReplayPort replay,
            List<IdentitySyncAuditEvent> audits) {
        return new ResponsibilitySyncService(
                repository,
                replay,
                new IdentitySyncTransactionPort() {
                    @Override
                    public <T> T execute(java.util.function.Supplier<T> work) {
                        return work.get();
                    }
                },
                audits::add,
                ignored -> {},
                ResponsibilitySyncServiceTest::trustedNow);
    }

    private static ResponsibilitySyncService service(
            FakeRepository repository,
            IdentityReplayPort replay,
            List<IdentitySyncAuditEvent> audits,
            List<CommittedResponsibilityChangeSet> published) {
        return new ResponsibilitySyncService(
                repository,
                (relations, now) -> relations.stream()
                        .map(relation -> new ResponsibilityRecipientEvidence(
                                relation,
                                null,
                                null,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false))
                        .toList(),
                replay,
                new IdentitySyncTransactionPort() {
                    @Override
                    public <T> T execute(
                            java.util.function.Supplier<T> work) {
                        return work.get();
                    }
                },
                audits::add,
                ignored -> {},
                ResponsibilitySyncServiceTest::trustedNow,
                new AccessInvalidationChangePublisherPort() {
                    @Override
                    public void publish(CommittedIdentityChangeSet changeSet) {}

                    @Override
                    public void publish(
                            CommittedResponsibilityChangeSet changeSet) {
                        published.add(changeSet);
                    }
                });
    }

    private static NormalizedResponsibilityBatch batch(
            long fromWatermark, long toWatermark) {
        var relation = new AuthoritativeResponsibilityRelation(
                UUID.fromString("019c1234-0000-7000-8000-000000000302"),
                KEY.sourceId(),
                "rtok_" + "a".repeat(40),
                new ResponsibilityStudentSourceReference(
                        "RESPONSIBILITY-STUDENT-REF",
                        "resp-student-v2",
                        "stok_" + "b".repeat(40),
                        "c".repeat(64),
                        "d".repeat(64)),
                "e".repeat(64),
                "f".repeat(64),
                ResponsibilityType.PRIMARY,
                ResponsibilityStatus.ACTIVE,
                new EffectiveInterval(NOW.minusSeconds(60), null),
                toWatermark,
                toWatermark,
                toWatermark,
                1,
                "a".repeat(64));
        return new NormalizedResponsibilityBatch(
                UUID.fromString("019c1234-0000-7000-8000-000000000303"),
                KEY,
                "RESPONSIBILITY-BATCH-1.0.0",
                "RESPONSIBILITY-AUTHORITY-1.0.0",
                toWatermark,
                fromWatermark,
                toWatermark,
                Map.of("identity-authority|sandbox-0", 42L),
                NOW.minusSeconds(60),
                NOW,
                "0123456789abcdef0123456789abcdef",
                "a".repeat(64),
                "b".repeat(64),
                true,
                new byte[] {1},
                new byte[] {2},
                new byte[] {3},
                "config://test/responsibility-authority-inbox",
                "k1",
                List.of(relation));
    }

    private static NormalizedResponsibilityBatch heartbeat() {
        return new NormalizedResponsibilityBatch(
                UUID.fromString("019c1234-0000-7000-8000-000000000304"),
                KEY,
                "RESPONSIBILITY-BATCH-1.0.0",
                "RESPONSIBILITY-AUTHORITY-1.0.0",
                7,
                6,
                6,
                Map.of("identity-authority|sandbox-0", 42L),
                NOW.minusSeconds(60),
                NOW,
                "0123456789abcdef0123456789abcdef",
                "a".repeat(64),
                "b".repeat(64),
                true,
                new byte[] {1},
                new byte[] {2},
                new byte[] {3},
                "config://test/responsibility-authority-inbox",
                "k1",
                List.of());
    }

    private static NormalizedResponsibilityBatch v2Batch(
            long fromWatermark, long toWatermark) {
        var v1 = batch(fromWatermark, toWatermark).relations().getFirst();
        var relation = new AuthoritativeResponsibilityRelation(
                v1.relationId(), v1.sourceId(), v1.relationRefToken(),
                v1.studentSourceReference(),
                v1.counselorAccountRefDigest(),
                v1.collegeOrganizationRefDigest(),
                v1.responsibilityType(), v1.status(),
                v1.effectiveInterval(), v1.sourceVersion(),
                v1.sourceWatermark(), v1.recordVersion(),
                v1.aggregateVersion(), v1.payloadDigest(),
                AccessInvalidationChangeKind.CORRECTED,
                AccessInvalidationReason.SOURCE_CORRECTION,
                NOW,
                new AccessInvalidationLineageId(
                        "lin_" + "a".repeat(40)),
                null);
        NormalizedResponsibilityBatch source = batch(
                fromWatermark, toWatermark);
        return new NormalizedResponsibilityBatch(
                source.batchId(), source.key(), source.schemaVersion(),
                "RESPONSIBILITY-AUTHORITY-2.0.0",
                source.sourceVersion(), source.fromWatermark(),
                source.toWatermark(),
                source.supportingIdentityOrgWatermarks(),
                source.sourceVisibleAt(), source.observedAt(),
                source.traceId(), source.envelopeDigest(),
                source.signatureDigest(), source.signatureVerified(),
                source.encryptedEnvelope(), source.wrappedDataKey(),
                source.encryptionNonce(), source.encryptionKeyRef(),
                source.encryptionKeyVersion(), List.of(relation));
    }

    private static NormalizedResponsibilityBatch v2Heartbeat() {
        NormalizedResponsibilityBatch source = heartbeat();
        return new NormalizedResponsibilityBatch(
                source.batchId(),
                source.key(),
                source.schemaVersion(),
                "RESPONSIBILITY-AUTHORITY-2.0.0",
                source.sourceVersion(),
                source.fromWatermark(),
                source.toWatermark(),
                source.supportingIdentityOrgWatermarks(),
                source.sourceVisibleAt(),
                source.observedAt(),
                source.traceId(),
                source.envelopeDigest(),
                source.signatureDigest(),
                source.signatureVerified(),
                source.encryptedEnvelope(),
                source.wrappedDataKey(),
                source.encryptionNonce(),
                source.encryptionKeyRef(),
                source.encryptionKeyVersion(),
                List.of());
    }

    private static NormalizedResponsibilityBatch withRelations(
            NormalizedResponsibilityBatch source,
            List<AuthoritativeResponsibilityRelation> relations) {
        return new NormalizedResponsibilityBatch(
                source.batchId(),
                source.key(),
                source.schemaVersion(),
                source.contractVersion(),
                source.sourceVersion(),
                source.fromWatermark(),
                source.toWatermark(),
                source.supportingIdentityOrgWatermarks(),
                source.sourceVisibleAt(),
                source.observedAt(),
                source.traceId(),
                source.envelopeDigest(),
                source.signatureDigest(),
                source.signatureVerified(),
                source.encryptedEnvelope(),
                source.wrappedDataKey(),
                source.encryptionNonce(),
                source.encryptionKeyRef(),
                source.encryptionKeyVersion(),
                relations);
    }

    private static TrustedTime trustedNow() {
        return new TrustedTime(
                NOW,
                new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(50),
                        "evidence://signed/clock/campus-ntp-a.json"));
    }

    private static final class FakeRepository implements ResponsibilitySyncRepository {
        private Optional<IdentityCheckpoint> checkpoint = Optional.of(
                new IdentityCheckpoint(
                        KEY,
                        6,
                        6,
                        6,
                        NOW.minusSeconds(10),
                        IdentitySourceHealth.HEALTHY,
                        IdentityProjectionFreshness.FRESH));
        private Optional<ResponsibilityRecordState> current = Optional.empty();
        private Optional<IdentityCheckpoint> v2Checkpoint = Optional.empty();
        private Optional<ResponsibilityRecordState> v2Current = Optional.empty();
        private Optional<AccessInvalidationLineageId> boundLineage =
                Optional.empty();
        private final List<IdentitySyncRejection> rejections = new ArrayList<>();
        private long identityWatermark = 42;
        private boolean leaseCurrent = true;
        private boolean applied;
        private boolean v2Applied;
        private boolean v2Active;
        private boolean heartbeatRecorded;
        private boolean v2HeartbeatRecorded;
        private UUID heartbeatBatchId;
        private String heartbeatEnvelopeDigest;
        private int exceptionWrites;
        private List<ResponsibilityScopeProjectionUpdate> scopeUpdates =
                List.of();
        private List<ResponsibilityExceptionAuditTransition> transitions =
                List.of();

        @Override
        public Optional<IdentityCheckpoint> checkpoint(CheckpointKey key) {
            return checkpoint;
        }

        @Override
        public Optional<String> envelopeDigest(UUID batchId) {
            return batchId.equals(heartbeatBatchId)
                    ? Optional.of(heartbeatEnvelopeDigest)
                    : Optional.empty();
        }

        @Override
        public Optional<ResponsibilityRecordState> currentRecord(
                CheckpointKey key, String relationRefToken) {
            return current;
        }

        @Override
        public Optional<IdentityCheckpoint> v2ShadowCheckpoint(
                CheckpointKey key) {
            return v2Checkpoint;
        }

        @Override
        public Optional<String> v2ShadowEnvelopeDigest(UUID batchId) {
            return Optional.empty();
        }

        @Override
        public Optional<ResponsibilityRecordState> v2ShadowCurrentRecord(
                CheckpointKey key, String relationRefToken) {
            return v2Current;
        }

        @Override
        public Optional<AccessInvalidationLineageId> v2BoundLineage(
                CheckpointKey key, String relationRefToken) {
            return boundLineage;
        }

        @Override
        public long identityOrgWatermark(String feedId, String partitionId) {
            return identityWatermark;
        }

        @Override
        public boolean leaseIsCurrent(IdentityLease lease) {
            return leaseCurrent;
        }

        @Override
        public void recordHeartbeat(
                NormalizedResponsibilityBatch batch,
                IdentityLease lease,
                Instant appliedAt) {
            heartbeatRecorded = true;
            heartbeatBatchId = batch.batchId();
            heartbeatEnvelopeDigest = batch.envelopeDigest();
        }

        @Override
        public void recordV2ShadowHeartbeat(
                NormalizedResponsibilityBatch batch,
                IdentityLease lease,
                Instant appliedAt) {
            v2HeartbeatRecorded = true;
        }

        @Override
        public void apply(
                NormalizedResponsibilityBatch batch,
                IdentityLease lease,
                Instant appliedAt) {
            applied = true;
        }

        @Override
        public void applyV2Shadow(
                NormalizedResponsibilityBatch batch,
                IdentityLease lease,
                Instant appliedAt,
                List<ResponsibilityScopeProjectionUpdate> updates) {
            v2Applied = true;
            scopeUpdates = List.copyOf(updates);
        }

        @Override
        public boolean v2ShadowActive(CheckpointKey key) {
            return v2Active;
        }

        @Override
        public void apply(
                NormalizedResponsibilityBatch batch,
                IdentityLease lease,
                Instant appliedAt,
                List<ResponsibilityScopeProjectionUpdate> updates) {
            scopeUpdates = List.copyOf(updates);
            apply(batch, lease, appliedAt);
        }

        @Override
        public List<ResponsibilityExceptionAuditTransition>
                applyWithAuditTransitions(
                        NormalizedResponsibilityBatch batch,
                        IdentityLease lease,
                        Instant appliedAt,
                        List<ResponsibilityScopeProjectionUpdate> updates) {
            apply(batch, lease, appliedAt, updates);
            return transitions;
        }

        @Override
        public List<AuthoritativeResponsibilityRelation> currentByStudentDigest(
                CheckpointKey key, String studentSourceRefDigest, Instant serverNow) {
            return List.of();
        }

        @Override
        public List<AuthoritativeResponsibilityRelation>
                v2ShadowCurrentByStudentDigest(
                        CheckpointKey key,
                        String studentSourceRefDigest,
                        Instant serverNow) {
            return List.of();
        }

        @Override
        public void reject(IdentitySyncRejection rejection) {
            rejections.add(rejection);
        }
    }
}
