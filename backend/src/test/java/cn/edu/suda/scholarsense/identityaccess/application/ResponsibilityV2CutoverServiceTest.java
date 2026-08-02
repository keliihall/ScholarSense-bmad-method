package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationState;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationChangeKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationJob;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageHead;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReason;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientDecision;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientEvidence;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientReason;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStudentSourceReference;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityType;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResponsibilityV2CutoverServiceTest {
    private static final ResponsibilityV2CutoverCommandSignaturePort
            COMMAND_SIGNATURES = ignored -> "d".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final String DIGEST = "a".repeat(64);
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "responsibility-authority",
            "sandbox-0",
            "responsibility");
    private static final AccessInvalidationLineageId LINEAGE =
            new AccessInvalidationLineageId("lin_" + "a".repeat(40));
    private static final UUID ROOT_EVENT = UUID.fromString(
            "019c0000-0000-7000-8000-000000000501");
    private static final UUID SNAPSHOT_ID = UUID.fromString(
            "019c0000-0000-7000-8000-000000000599");

    @Test
    void snapshotUsesExactRecipientEvidenceAndRecoveryFailsClosed() {
        var relation = relation(
                ROOT_EVENT,
                AccessInvalidationChangeKind.CORRECTED,
                AccessInvalidationReason.SOURCE_CORRECTION,
                null,
                ResponsibilityStatus.ACTIVE,
                NOW.plusSeconds(3600));
        var evidence = evidence(relation, true, false, true);
        var scope = scope(
                relation,
                ResponsibilityRecipientDecision.invalid(
                        ResponsibilityRecipientReason.NON_R1_RECIPIENT),
                evidence);

        AccessInvalidationFact fact = ResponsibilityInvalidationFactFactory
                .create(batch(0, 1, relation), List.of(scope), relation, 1);

        assertTrue(fact.authorizationSnapshot().accountActive());
        assertFalse(fact.authorizationSnapshot().r1EmploymentValid());
        assertTrue(fact.authorizationSnapshot().collegeActive());
        assertTrue(fact.authorizationSnapshot().relationEffective());

        var recovery = relation(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000502"),
                AccessInvalidationChangeKind.REVALIDATED,
                AccessInvalidationReason.RECONCILIATION_RECOVERED,
                ROOT_EVENT,
                ResponsibilityStatus.ACTIVE,
                NOW.plusSeconds(3600));
        var invalidRecoveryScope = scope(
                recovery,
                ResponsibilityRecipientDecision.invalid(
                        ResponsibilityRecipientReason.COLLEGE_INACTIVE),
                evidence(recovery, true, true, false));
        IdentitySyncException rejected = assertThrows(
                IdentitySyncException.class,
                () -> ResponsibilityInvalidationFactFactory.create(
                        batch(1, 2, recovery),
                        List.of(invalidRecoveryScope),
                        recovery,
                        2));
        assertEquals(
                "RESPONSIBILITY_V2_RECOVERY_EVIDENCE_INVALID",
                rejected.code());

        var competingRelation = relation(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000504"),
                AccessInvalidationChangeKind.CORRECTED,
                AccessInvalidationReason.SOURCE_CORRECTION,
                null,
                ResponsibilityStatus.ACTIVE,
                NOW.plusSeconds(3600),
                "c",
                new AccessInvalidationLineageId(
                        "lin_" + "c".repeat(40)));
        var conflictingRecoveryScope =
                new ResponsibilityScopeProjectionUpdate(
                        recovery.studentSourceReference()
                                .equivalenceDomain(),
                        List.of(recovery, competingRelation),
                        ResponsibilityRecipientDecision.invalid(
                                ResponsibilityRecipientReason
                                        .MULTIPLE_RECIPIENTS),
                        List.of(
                                evidence(recovery, true, true, true),
                                evidence(
                                        competingRelation,
                                        true,
                                        true,
                                        true)));
        IdentitySyncException conflictingRecovery = assertThrows(
                IdentitySyncException.class,
                () -> ResponsibilityInvalidationFactFactory.create(
                        batch(1, 2, recovery),
                        List.of(conflictingRecoveryScope),
                        recovery,
                        2));
        assertEquals(
                "RESPONSIBILITY_V2_RECOVERY_EVIDENCE_INVALID",
                conflictingRecovery.code());

        var validRecoveryScope = scope(
                recovery, validDecision(recovery),
                evidence(recovery, true, true, true));
        AccessInvalidationFact recovered =
                ResponsibilityInvalidationFactFactory.create(
                        batch(1, 2, recovery),
                        List.of(validRecoveryScope), recovery, 2);
        assertEquals(
                AccessInvalidationAuthorizationState.REVALIDATED,
                recovered.authorizationSnapshot().currentState());
    }

    @Test
    void cutoverMaterializesReplayRootSchedulesBoundExpiryAndAllowsSuccessor() {
        var rootRelation = relation(
                ROOT_EVENT,
                AccessInvalidationChangeKind.CORRECTED,
                AccessInvalidationReason.SOURCE_CORRECTION,
                null,
                ResponsibilityStatus.ACTIVE,
                NOW.plusSeconds(3600));
        var rootScope = scope(
                rootRelation, validDecision(rootRelation),
                evidence(rootRelation, true, true, true));
        AccessInvalidationFact root = ResponsibilityInvalidationFactFactory
                .create(
                        batch(0, 1, rootRelation),
                        List.of(rootScope), rootRelation, 1);
        var repository = new FakeRepository(
                root,
                new ResponsibilityV2ExpiryCandidate(
                        LINEAGE, ROOT_EVENT, 1,
                        NOW.plusSeconds(3600), TRACE));
        var store = new FakeStore();
        var expiries = new FakeExpiry();
        AtomicInteger ids = new AtomicInteger(600);
        AccessInvalidationIdPort identifiers = ignored -> UUID.fromString(
                "019c0000-0000-7000-8000-000000000"
                        + ids.incrementAndGet());
        var cutover = new ResponsibilityV2CutoverService(
                repository,
                directTransactions(),
                ResponsibilityV2CutoverServiceTest::trustedNow,
                store,
                ignored -> "{}",
                identifiers,
                ignored -> {},
                expiries);

        cutover.activate(new ResponsibilityV2CutoverRequest(
                KEY, SNAPSHOT_ID, TRACE));

        assertTrue(repository.materialized);
        assertTrue(repository.activated);
        assertEquals(ROOT_EVENT, store.head(LINEAGE.value())
                .orElseThrow().eventId());
        assertEquals(ROOT_EVENT, expiries.scheduledEventId);
        assertEquals(1, expiries.scheduledVersion);

        var successor = relation(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000503"),
                AccessInvalidationChangeKind.REVOKED,
                AccessInvalidationReason.DIRECT_RESPONSIBILITY_CHANGE,
                ROOT_EVENT,
                ResponsibilityStatus.INACTIVE,
                NOW);
        var successorBatch = batch(1, 2, successor);
        var successorScope = scope(
                successor,
                ResponsibilityRecipientDecision.invalid(
                        ResponsibilityRecipientReason.ZERO_RECIPIENT),
                evidence(successor, true, true, true));
        var publisher = new AccessInvalidationPublisherService(
                store,
                store,
                expiries,
                ignored -> "{}",
                identifiers,
                ignored -> {});

        publisher.publish(new CommittedResponsibilityChangeSet(
                successorBatch,
                new IdentitySyncResult(
                        IdentitySyncOutcome.APPLIED,
                        "RESPONSIBILITY_SYNC_APPLIED", 2, 2, 2),
                List.of(successorScope), NOW));

        AccessInvalidationLineageHead head = store.head(LINEAGE.value())
                .orElseThrow();
        assertEquals(successor.relationId(), head.eventId());
        assertEquals(2, head.aggregateVersion());
    }

    @Test
    void reconciliationUsesAuthenticatedSnapshotAndRepositoryEvidence() {
        var rootRelation = relation(
                ROOT_EVENT,
                AccessInvalidationChangeKind.CORRECTED,
                AccessInvalidationReason.SOURCE_CORRECTION,
                null,
                ResponsibilityStatus.ACTIVE,
                NOW.plusSeconds(3600));
        AccessInvalidationFact root = ResponsibilityInvalidationFactFactory
                .create(
                        batch(0, 1, rootRelation),
                        List.of(scope(
                                rootRelation,
                                validDecision(rootRelation),
                                evidence(rootRelation, true, true, true))),
                        rootRelation,
                        1);
        var repository = new FakeRepository(
                root,
                new ResponsibilityV2ExpiryCandidate(
                        LINEAGE, ROOT_EVENT, 1,
                        NOW.plusSeconds(3600), TRACE));
        ResponsibilityFullSnapshot snapshot = snapshot(true);
        var service = new ResponsibilityV2CutoverService(
                repository,
                directTransactions(),
                ResponsibilityV2CutoverServiceTest::trustedNow,
                new FakeStore(),
                ignored -> "{}",
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000699"),
                ignored -> {},
                new FakeExpiry(),
                snapshotSource(snapshot),
                ignored -> {});

        ResponsibilityV2ReconciliationEvidence reconciled =
                service.recordReconciliation(
                        new ResponsibilityV2ReconciliationRequest(
                                KEY, LocalDate.of(2026, 8, 1), TRACE));

        assertEquals(SNAPSHOT_ID, reconciled.snapshotId());
        assertTrue(reconciled.matched());
        assertTrue(repository.reconciliationRecorded);

        var invalidSource = new ResponsibilityV2CutoverService(
                repository,
                directTransactions(),
                ResponsibilityV2CutoverServiceTest::trustedNow,
                new FakeStore(),
                ignored -> "{}",
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000698"),
                ignored -> {},
                new FakeExpiry(),
                snapshotSource(snapshot(false)),
                ignored -> {});
        IdentitySyncException rejected = assertThrows(
                IdentitySyncException.class,
                () -> invalidSource.recordReconciliation(
                        new ResponsibilityV2ReconciliationRequest(
                                KEY, LocalDate.of(2026, 8, 1), TRACE)));
        assertEquals(
                "RESPONSIBILITY_V2_RECONCILIATION_SNAPSHOT_INVALID",
                rejected.code());
    }

    @Test
    void approvedCommandIsDurableAuditedAndActivatedIdempotently() {
        var rootRelation = relation(
                ROOT_EVENT,
                AccessInvalidationChangeKind.CORRECTED,
                AccessInvalidationReason.SOURCE_CORRECTION,
                null,
                ResponsibilityStatus.ACTIVE,
                NOW.plusSeconds(3600));
        AccessInvalidationFact root = ResponsibilityInvalidationFactFactory
                .create(
                        batch(0, 1, rootRelation),
                        List.of(scope(
                                rootRelation,
                                validDecision(rootRelation),
                                evidence(rootRelation, true, true, true))),
                        rootRelation,
                        1);
        var repository = new FakeRepository(
                root,
                new ResponsibilityV2ExpiryCandidate(
                        LINEAGE, ROOT_EVENT, 1,
                        NOW.plusSeconds(3600), TRACE));
        var audits = new ArrayList<IdentitySyncAuditEvent>();
        var service = new ResponsibilityV2CutoverService(
                repository,
                directTransactions(),
                ResponsibilityV2CutoverServiceTest::trustedNow,
                new FakeStore(),
                ignored -> "{}",
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000697"),
                ignored -> {},
                new FakeExpiry(),
                snapshotSource(snapshot(true)),
                audits::add,
                "a".repeat(64),
                COMMAND_SIGNATURES);
        UUID commandId = UUID.fromString(
                "019c0000-0000-7000-8000-000000000596");

        ResponsibilityV2CutoverCommand command =
                ResponsibilityV2CutoverCommand.signed(
                        commandId,
                        KEY,
                        LocalDate.of(2026, 8, 1),
                        ResponsibilityV2CutoverCommand.CONTROLLED_OPERATOR,
                        ResponsibilityV2CutoverCommand.APPROVAL,
                        "a".repeat(64),
                        NOW,
                        TRACE,
                        COMMAND_SIGNATURES);
        IdentityCheckpoint activated = service.execute(command);
        IdentityCheckpoint replayed = service.execute(command);

        assertEquals(1, activated.sourceVersion());
        assertEquals(activated, replayed);
        assertEquals(
                "activated",
                repository.commandState.orElseThrow().status());
        assertEquals(
                List.of(
                        "responsibility.v2.cutover.requested",
                        "responsibility.v2.reconciled",
                        "responsibility.v2.activated"),
                audits.stream()
                        .map(IdentitySyncAuditEvent::action)
                        .toList());
        assertTrue(audits.stream().allMatch(event ->
                ResponsibilityV2CutoverCommand.CONTROLLED_OPERATOR
                        .equals(event.actorReference())));

        IdentitySyncException conflict = assertThrows(
                IdentitySyncException.class,
                () -> service.execute(ResponsibilityV2CutoverCommand.signed(
                        commandId,
                        KEY,
                        LocalDate.of(2026, 8, 1),
                        ResponsibilityV2CutoverCommand.CONTROLLED_OPERATOR,
                        ResponsibilityV2CutoverCommand.APPROVAL,
                        "a".repeat(64),
                        NOW,
                        "f".repeat(32),
                        COMMAND_SIGNATURES)));
        assertEquals(
                "RESPONSIBILITY_V2_CUTOVER_COMMAND_CONFLICT",
                conflict.code());
    }

    @Test
    void unapprovedProfileIsDeniedBeforeSnapshotRead() {
        var repository = new FakeRepository(
                null,
                null);
        var service = new ResponsibilityV2CutoverService(
                repository,
                directTransactions(),
                ResponsibilityV2CutoverServiceTest::trustedNow,
                new FakeStore(),
                ignored -> "{}",
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000696"),
                ignored -> {},
                new FakeExpiry(),
                snapshotSource(snapshot(true)),
                ignored -> {},
                "a".repeat(64),
                COMMAND_SIGNATURES);

        IdentitySyncException denied = assertThrows(
                IdentitySyncException.class,
                () -> service.execute(ResponsibilityV2CutoverCommand.signed(
                        UUID.fromString(
                                "019c0000-0000-7000-8000-000000000595"),
                        KEY,
                        LocalDate.of(2026, 8, 1),
                        ResponsibilityV2CutoverCommand.CONTROLLED_OPERATOR,
                        ResponsibilityV2CutoverCommand.APPROVAL,
                        "b".repeat(64),
                        NOW,
                        TRACE,
                        COMMAND_SIGNATURES)));

        assertEquals(
                "RESPONSIBILITY_V2_CUTOVER_COMMAND_UNAUTHORIZED",
                denied.code());
        assertEquals(
                "denied",
                repository.commandState.orElseThrow().status());
        assertFalse(repository.reconciliationRecorded);
    }

    @Test
    void invalidCommandMacIsDeniedBeforeSnapshotRead() {
        var repository = new FakeRepository(null, null);
        var service = new ResponsibilityV2CutoverService(
                repository,
                directTransactions(),
                ResponsibilityV2CutoverServiceTest::trustedNow,
                new FakeStore(),
                ignored -> "{}",
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000694"),
                ignored -> {},
                new FakeExpiry(),
                snapshotSource(snapshot(true)),
                ignored -> {},
                "a".repeat(64),
                COMMAND_SIGNATURES);
        ResponsibilityV2CutoverCommand tampered =
                new ResponsibilityV2CutoverCommand(
                        UUID.fromString(
                                "019c0000-0000-7000-8000-000000000594"),
                        KEY,
                        LocalDate.of(2026, 8, 1),
                        ResponsibilityV2CutoverCommand.CONTROLLED_OPERATOR,
                        ResponsibilityV2CutoverCommand.APPROVAL,
                        "a".repeat(64),
                        NOW,
                        TRACE,
                        "e".repeat(64));

        IdentitySyncException denied = assertThrows(
                IdentitySyncException.class,
                () -> service.execute(tampered));

        assertEquals(
                "RESPONSIBILITY_V2_CUTOVER_COMMAND_UNAUTHORIZED",
                denied.code());
        assertEquals(
                "denied",
                repository.commandState.orElseThrow().status());
        assertFalse(repository.reconciliationRecorded);
    }

    private static ResponsibilityScopeProjectionUpdate scope(
            AuthoritativeResponsibilityRelation relation,
            ResponsibilityRecipientDecision decision,
            ResponsibilityRecipientEvidence evidence) {
        return new ResponsibilityScopeProjectionUpdate(
                relation.studentSourceReference().equivalenceDomain(),
                List.of(relation), decision, List.of(evidence));
    }

    private static ResponsibilityRecipientEvidence evidence(
            AuthoritativeResponsibilityRelation relation,
            boolean accountActive,
            boolean r1Active,
            boolean collegeActive) {
        return new ResponsibilityRecipientEvidence(
                relation,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000511"),
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000512"),
                true,
                accountActive,
                r1Active,
                true,
                collegeActive,
                true);
    }

    private static ResponsibilityRecipientDecision validDecision(
            AuthoritativeResponsibilityRelation relation) {
        return new ResponsibilityRecipientDecision(
                cn.edu.suda.scholarsense.identityaccess.domain
                        .ResponsibilityRecipientValidity.VALID,
                ResponsibilityRecipientReason.VALID,
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000511"),
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000512"),
                relation.sourceVersion(), relation.sourceWatermark(),
                relation.aggregateVersion());
    }

    private static AuthoritativeResponsibilityRelation relation(
            UUID eventId,
            AccessInvalidationChangeKind kind,
            AccessInvalidationReason reason,
            UUID supersedes,
            ResponsibilityStatus status,
            Instant effectiveTo) {
        return relation(
                eventId,
                kind,
                reason,
                supersedes,
                status,
                effectiveTo,
                "b",
                LINEAGE);
    }

    private static AuthoritativeResponsibilityRelation relation(
            UUID eventId,
            AccessInvalidationChangeKind kind,
            AccessInvalidationReason reason,
            UUID supersedes,
            ResponsibilityStatus status,
            Instant effectiveTo,
            String tokenCharacter,
            AccessInvalidationLineageId lineage) {
        return new AuthoritativeResponsibilityRelation(
                eventId,
                KEY.sourceId(),
                "rtok_" + tokenCharacter.repeat(40),
                new ResponsibilityStudentSourceReference(
                        "RESPONSIBILITY-STUDENT-REF",
                        "resp-student-v2",
                        "stok_" + "c".repeat(40),
                        "d".repeat(64),
                        "e".repeat(64)),
                "f".repeat(64),
                "1".repeat(64),
                ResponsibilityType.PRIMARY,
                status,
                new EffectiveInterval(NOW.minusSeconds(60), effectiveTo),
                supersedes == null ? 1 : 2,
                supersedes == null ? 1 : 2,
                supersedes == null ? 1 : 2,
                supersedes == null ? 1 : 2,
                supersedes == null ? "2".repeat(64) : "3".repeat(64),
                kind,
                reason,
                NOW,
                lineage,
                supersedes);
    }

    private static NormalizedResponsibilityBatch batch(
            long from, long to,
            AuthoritativeResponsibilityRelation relation) {
        return new NormalizedResponsibilityBatch(
                to == 1
                        ? UUID.fromString(
                                "019c0000-0000-7000-8000-000000000521")
                        : UUID.fromString(
                                "019c0000-0000-7000-8000-000000000522"),
                KEY,
                "RESPONSIBILITY-BATCH-1.0.0",
                "RESPONSIBILITY-AUTHORITY-2.0.0",
                to,
                from,
                to,
                Map.of("identity-authority|sandbox-0", 42L),
                NOW.minusSeconds(60),
                NOW,
                TRACE,
                "4".repeat(64),
                "5".repeat(64),
                true,
                new byte[] {1},
                new byte[] {2},
                new byte[] {3},
                "config://test/responsibility-inbox",
                "k1",
                List.of(relation));
    }

    private static IdentitySyncTransactionPort directTransactions() {
        return new IdentitySyncTransactionPort() {
            @Override
            public <T> T execute(java.util.function.Supplier<T> work) {
                return work.get();
            }
        };
    }

    private static ResponsibilityFullSnapshot snapshot(
            boolean signatureVerified) {
        ResponsibilityV2LineageManifest lineage = lineageManifest();
        return new ResponsibilityFullSnapshot(
                SNAPSHOT_ID,
                KEY,
                "RESPONSIBILITY-SNAPSHOT-1.0.0",
                "RESPONSIBILITY-AUTHORITY-2.0.0",
                LocalDate.of(2026, 8, 1),
                NOW,
                1,
                1,
                Map.of("identity-authority|sandbox-0", 42L),
                true,
                true,
                List.of("sandbox-0"),
                1,
                DIGEST,
                DIGEST,
                DIGEST,
                signatureVerified,
                List.of(new ResponsibilitySnapshotEntry(
                        "rtok_" + "b".repeat(40),
                        "d".repeat(64),
                        1,
                        "2".repeat(64),
                        true,
                        true)),
                1,
                ResponsibilityV2LineageManifest.digest(
                        List.of(lineage)),
                List.of(lineage),
                TRACE);
    }

    private static ResponsibilityV2LineageManifest lineageManifest() {
        return new ResponsibilityV2LineageManifest(
                "rtok_" + "b".repeat(40),
                LINEAGE,
                ROOT_EVENT,
                ROOT_EVENT,
                1,
                DIGEST);
    }

    private static ResponsibilityFullSnapshotSourcePort snapshotSource(
            ResponsibilityFullSnapshot snapshot) {
        return new ResponsibilityFullSnapshotSourcePort() {
            @Override
            public ResponsibilityFullSnapshot fetch(
                    CheckpointKey key,
                    LocalDate businessDate,
                    String traceId) {
                throw new AssertionError("V1 snapshot path used");
            }

            @Override
            public ResponsibilityFullSnapshot fetchVersion(
                    CheckpointKey key,
                    LocalDate businessDate,
                    String contractVersion,
                    String traceId) {
                assertEquals(
                        "RESPONSIBILITY-AUTHORITY-2.0.0",
                        contractVersion);
                return snapshot;
            }
        };
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

    private static final class FakeRepository
            implements ResponsibilitySyncRepository {
        private final List<AccessInvalidationFact> facts;
        private final List<ResponsibilityV2ExpiryCandidate> expiries;
        private boolean materialized;
        private boolean activated;
        private boolean reconciliationRecorded;
        private Optional<IdentityCheckpoint> liveCheckpoint =
                Optional.empty();
        private ResponsibilityV2ProjectionFingerprint fingerprint =
                new ResponsibilityV2ProjectionFingerprint(
                        1,
                        DIGEST,
                        1,
                        ResponsibilityV2LineageManifest.digest(
                                List.of(lineageManifest())),
                        0);
        private Optional<ResponsibilityV2CutoverCommandState> commandState =
                Optional.empty();

        private FakeRepository(
                AccessInvalidationFact fact,
                ResponsibilityV2ExpiryCandidate expiry) {
            facts = fact == null ? List.of() : List.of(fact);
            expiries = expiry == null ? List.of() : List.of(expiry);
        }

        @Override
        public Optional<ResponsibilityV2CutoverCommandState>
                v2CutoverCommand(UUID commandId) {
            return commandState.filter(
                    state -> state.commandId().equals(commandId));
        }

        @Override
        public void beginV2CutoverCommand(
                ResponsibilityV2CutoverCommand command) {
            if (commandState.isPresent()) {
                throw new IdentitySyncException(
                        "RESPONSIBILITY_V2_CUTOVER_COMMAND_DUPLICATE");
            }
            commandState = Optional.of(
                    new ResponsibilityV2CutoverCommandState(
                            command.commandId(),
                            command.canonicalDigest(),
                            "requested",
                            "RESPONSIBILITY_V2_CUTOVER_REQUESTED",
                            null,
                            null));
        }

        @Override
        public void finishV2CutoverCommand(
                UUID commandId,
                String status,
                String reasonCode,
                UUID snapshotId,
                Instant completedAt) {
            commandState = Optional.of(
                    new ResponsibilityV2CutoverCommandState(
                            commandId,
                            commandState.orElseThrow().commandDigest(),
                            status,
                            reasonCode,
                            snapshotId,
                            completedAt));
        }

        @Override
        public ResponsibilityV2ProjectionFingerprint v2ShadowFingerprint(
                CheckpointKey key) {
            return fingerprint;
        }

        @Override
        public ResponsibilityV2ReconciliationEvidence
                recordV2Reconciliation(
                        ResponsibilityFullSnapshot snapshot,
                        Instant reconciledAt) {
            reconciliationRecorded = true;
            return new ResponsibilityV2ReconciliationEvidence(
                    snapshot.key(),
                    snapshot.snapshotId(),
                    snapshot.sourceVersion(),
                    snapshot.throughWatermark(),
                    snapshot.expectedCount(),
                    fingerprint.recordCount(),
                    snapshot.canonicalDigest(),
                    fingerprint.canonicalDigest(),
                    snapshot.lineageCount(),
                    fingerprint.lineageCount(),
                    snapshot.canonicalLineageDigest(),
                    fingerprint.canonicalLineageDigest(),
                    fingerprint.lineageConflictCount(),
                    snapshot.envelopeDigest(),
                    snapshot.signatureDigest(),
                    reconciledAt,
                    snapshot.traceId());
        }

        @Override
        public List<AccessInvalidationFact> v2ShadowInvalidationFacts(
                CheckpointKey key) {
            return facts;
        }

        @Override
        public List<ResponsibilityV2ExpiryCandidate>
                v2ShadowExpiryCandidates(
                        CheckpointKey key, Instant activatedAt) {
            return expiries;
        }

        @Override
        public void markV2InvalidationReplayMaterialized(
                ResponsibilityV2CutoverRequest request, int factCount) {
            assertEquals(1, factCount);
            materialized = true;
        }

        @Override
        public IdentityCheckpoint activateV2Shadow(
                ResponsibilityV2CutoverRequest request,
                Instant activatedAt) {
            if (!materialized) {
                throw new AssertionError("materialization gate skipped");
            }
            activated = true;
            IdentityCheckpoint checkpoint = new IdentityCheckpoint(
                    KEY, 1, 1, 1, activatedAt,
                    IdentitySourceHealth.HEALTHY,
                    IdentityProjectionFreshness.FRESH);
            liveCheckpoint = Optional.of(checkpoint);
            return checkpoint;
        }

        @Override
        public Optional<IdentityCheckpoint> checkpoint(CheckpointKey key) {
            return liveCheckpoint;
        }

        @Override
        public Optional<String> envelopeDigest(UUID batchId) {
            return Optional.empty();
        }

        @Override
        public Optional<ResponsibilityRecordState> currentRecord(
                CheckpointKey key, String relationRefToken) {
            return Optional.empty();
        }

        @Override
        public long identityOrgWatermark(String feedId, String partitionId) {
            return 0;
        }

        @Override
        public void apply(
                NormalizedResponsibilityBatch batch,
                IdentityLease lease,
                Instant appliedAt) {}

        @Override
        public List<AuthoritativeResponsibilityRelation>
                currentByStudentDigest(
                        CheckpointKey key,
                        String studentEquivalenceDigest,
                        Instant serverNow) {
            return List.of();
        }

        @Override
        public void reject(IdentitySyncRejection rejection) {}

        @Override
        public boolean leaseIsCurrent(IdentityLease lease) {
            return true;
        }
    }

    private static final class FakeStore
            implements AccessInvalidationStorePort,
                    AccessInvalidationImpactJobPort {
        private final Map<String, AccessInvalidationLineageHead> heads =
                new LinkedHashMap<>();
        private final List<AccessInvalidationAppendCommand> commands =
                new ArrayList<>();

        @Override
        public AccessInvalidationFact append(
                AccessInvalidationAppendCommand command) {
            AccessInvalidationFact fact = command.fact();
            assertEquals(
                    heads.containsKey(fact.lineageId().value())
                            ? heads.get(fact.lineageId().value())
                                    .aggregateVersion()
                            : 0,
                    command.expectedHeadVersion());
            heads.put(
                    fact.lineageId().value(),
                    new AccessInvalidationLineageHead(
                            fact.lineageId(), fact.eventId(),
                            fact.aggregateVersion()));
            commands.add(command);
            return fact;
        }

        @Override
        public Optional<AccessInvalidationLineageHead> head(
                String lineageId) {
            return Optional.ofNullable(heads.get(lineageId));
        }

        @Override
        public Optional<AccessInvalidationFact> find(UUID eventId) {
            return commands.stream().map(command -> command.fact())
                    .filter(fact -> fact.eventId().equals(eventId))
                    .findFirst();
        }

        @Override
        public Optional<AccessInvalidationFact> latest(String lineageId) {
            return commands.stream().map(command -> command.fact())
                    .filter(fact -> fact.lineageId().value().equals(lineageId))
                    .reduce((left, right) -> right);
        }

        @Override
        public long fencingToken(String lineageId) {
            return heads.containsKey(lineageId) ? 1 : 0;
        }

        @Override
        public void enqueue(
                UUID jobId,
                cn.edu.suda.scholarsense.identityaccess.domain
                        .AccessInvalidationCause cause,
                Instant retainUntil) {}
    }

    private static final class FakeExpiry
            implements AccessInvalidationExpiryPort {
        private UUID scheduledEventId;
        private long scheduledVersion;

        @Override
        public AccessInvalidationJob enqueue(
                UUID jobId,
                AccessInvalidationLineageId lineageId,
                Instant effectiveTo,
                String traceId) {
            return null;
        }

        @Override
        public AccessInvalidationJob enqueue(
                UUID jobId,
                AccessInvalidationLineageId lineageId,
                UUID eventId,
                long version,
                Instant effectiveTo,
                AccessInvalidationReason reason,
                String traceId,
                Instant scheduledAt) {
            scheduledEventId = eventId;
            scheduledVersion = version;
            return null;
        }
    }
}
