package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.AccessInvalidationEventJsonCodec;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAggregateType;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationChangeKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageHead;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReason;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeAccount;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientDecision;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientEvidence;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientReason;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStudentSourceReference;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AccessInvalidationPublisherServiceTest {
    private static final Instant NOW =
            Instant.parse("2026-07-31T12:00:00Z");
    private static final String TRACE =
            "0123456789abcdef0123456789abcdef";

    @Test
    void responsibilityV2PublishesDirectSelfContainedFactAndV1IsBlocked() {
        var store = new FakeStore();
        var service = service(store);
        NormalizedResponsibilityBatch v2 = responsibilityBatch(
                "RESPONSIBILITY-AUTHORITY-2.0.0", true);

        service.publish(new CommittedResponsibilityChangeSet(
                v2,
                appliedResult(),
                scopeUpdates(v2),
                NOW));

        assertEquals(1, store.commands.size());
        AccessInvalidationAppendCommand command =
                store.commands.getFirst();
        assertEquals(
                AccessInvalidationChangeKind.REVOKED,
                command.fact().changeKind());
        assertEquals(
                AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE,
                command.fact().aggregateType());
        assertTrue(command.eventPayload().contains(
                "\"type\":\"scholarsense.identity-access.responsibility.changed.v1\""));
        assertTrue(command.eventPayload().contains(
                "\"reasonCode\":\"DIRECT_RESPONSIBILITY_CHANGE\""));
        assertFalse(command.eventPayload().contains("ACCOUNT-001"));

        IdentitySyncException blocked = assertThrows(
                IdentitySyncException.class,
                () -> service.publish(
                        new CommittedResponsibilityChangeSet(
                                responsibilityBatch(
                                        "RESPONSIBILITY-AUTHORITY-1.0.0",
                                        false),
                                appliedResult(),
                                List.of(),
                                NOW)));
        assertEquals("RESPONSIBILITY_V2_REPLAY_REQUIRED", blocked.code());
    }

    @Test
    void inactiveIdentityCreatesCauseAndPersistentImpactJobOnly() {
        var store = new FakeStore();
        var service = service(store);
        String externalDigest = "a".repeat(64);
        UUID eventId = UUID.fromString(
                "019c0000-0000-7000-8000-000000000301");
        var account = new AuthoritativeAccount(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000302"),
                "SRC-P0-RESPONSIBILITY-001",
                externalDigest,
                "actor_v1_k1_" + "b".repeat(64),
                AuthoritativeStatus.INACTIVE,
                new EffectiveInterval(NOW.minusSeconds(60), null),
                7,
                2);
        var sourceFact = new IdentitySourceFact(
                eventId,
                IdentityRecordKind.ACCOUNT,
                externalDigest,
                7,
                account.effectiveInterval(),
                "c".repeat(64),
                2);
        var batch = new NormalizedIdentityBatch(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000303"),
                new CheckpointKey(
                        "SRC-P0-RESPONSIBILITY-001",
                        "identity-authority",
                        "sandbox-0",
                        "identity-org"),
                "IDENTITY-AUTHORITY-BATCH-1.0.0",
                7,
                6,
                7,
                NOW.minusSeconds(60),
                NOW,
                TRACE,
                "IDENTITY-ROLE-MAPPING-1.0.0",
                "d".repeat(64),
                "e".repeat(64),
                "f".repeat(64),
                true,
                new byte[] {1},
                new byte[] {2},
                new byte[] {3},
                "config://test/identity-inbox",
                "k1",
                List.of(account),
                List.of(),
                List.of(),
                List.of(sourceFact));

        service.publish(new CommittedIdentityChangeSet(
                batch, appliedResult(), NOW));

        assertEquals(1, store.commands.size());
        assertEquals(
                AccessInvalidationReason.ACCOUNT_DISABLED,
                store.commands.getFirst().fact().reasonCode());
        assertEquals(
                AccessInvalidationAggregateType.IDENTITY_CAUSE,
                store.commands.getFirst().fact().aggregateType());
        assertEquals(
                "SRC-P0-RESPONSIBILITY-001",
                store.commands.getFirst().fact().sourceVector().sourceId());
        assertEquals(1, store.causes.size());
        assertEquals(eventId, store.causes.getFirst().causeEventId());
    }

    @Test
    void activeIdentityCorrectionPublishesSourceCorrectionCauseForRecoveryFanOut() {
        var store = new FakeStore();
        var service = service(store);
        NormalizedIdentityBatch batch = activeAccountBatch();

        service.publish(new CommittedIdentityChangeSet(
                batch, appliedResult(), NOW));

        assertEquals(1, store.commands.size());
        var fact = store.commands.getFirst().fact();
        assertEquals(AccessInvalidationChangeKind.CORRECTED, fact.changeKind());
        assertEquals(AccessInvalidationReason.SOURCE_CORRECTION, fact.reasonCode());
        assertTrue(fact.authorizationSnapshot().accountActive());
        assertFalse(fact.authorizationSnapshot().r1EmploymentValid());
        assertFalse(fact.authorizationSnapshot().collegeActive());
        assertEquals(1, store.causes.size());
        assertEquals(AccessInvalidationReason.SOURCE_CORRECTION,
                store.causes.getFirst().reasonCode());
    }

    @Test
    void lateActiveResponsibilityStillEnqueuesBoundExpiryCatchUp() {
        var store = new FakeStore();
        var service = service(store);
        Instant alreadyExpiredAt = NOW.minusSeconds(1);
        NormalizedResponsibilityBatch batch = responsibilityBatch(
                "RESPONSIBILITY-AUTHORITY-2.0.0",
                true,
                ResponsibilityStatus.ACTIVE,
                alreadyExpiredAt);

        service.publish(new CommittedResponsibilityChangeSet(
                batch,
                appliedResult(),
                scopeUpdates(batch),
                NOW));

        assertEquals(1, store.expiries.size());
        var fact = store.commands.getFirst().fact();
        var expiry = store.expiries.getFirst();
        assertEquals(fact.eventId(), expiry.scheduledEventId());
        assertEquals(fact.aggregateVersion(),
                expiry.scheduledAggregateVersion());
        assertEquals(alreadyExpiredAt, expiry.effectiveTo());
        assertEquals(AccessInvalidationReason.RELATION_EXPIRED,
                expiry.reasonCode());
        assertEquals(NOW, expiry.scheduledAt());
    }

    private static AccessInvalidationPublisherService service(
            FakeStore store) {
        AtomicInteger sequence = new AtomicInteger(400);
        return new AccessInvalidationPublisherService(
                store,
                store,
                store,
                new AccessInvalidationEventJsonCodec(
                        new ObjectMapper()),
                ignored -> UUID.fromString(
                        "019c0000-0000-7000-8000-000000000"
                                + sequence.incrementAndGet()));
    }

    private static NormalizedResponsibilityBatch responsibilityBatch(
            String contractVersion, boolean metadata) {
        return responsibilityBatch(
                contractVersion,
                metadata,
                ResponsibilityStatus.INACTIVE,
                NOW);
    }

    private static NormalizedResponsibilityBatch responsibilityBatch(
            String contractVersion,
            boolean metadata,
            ResponsibilityStatus status,
            Instant effectiveTo) {
        var relation = new AuthoritativeResponsibilityRelation(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000311"),
                "SRC-P0-RESPONSIBILITY-001",
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
                status,
                new EffectiveInterval(
                        NOW.minusSeconds(60), effectiveTo),
                7,
                7,
                7,
                1,
                "a".repeat(64),
                metadata
                        ? status == ResponsibilityStatus.ACTIVE
                                ? AccessInvalidationChangeKind.CORRECTED
                                : AccessInvalidationChangeKind.REVOKED
                        : null,
                metadata
                        ? status == ResponsibilityStatus.ACTIVE
                                ? AccessInvalidationReason.SOURCE_CORRECTION
                                : AccessInvalidationReason
                                        .DIRECT_RESPONSIBILITY_CHANGE
                        : null,
                metadata ? NOW : null,
                metadata
                        ? new AccessInvalidationLineageId(
                                "lin_"
                                        + "a".repeat(40))
                        : null,
                null);
        return new NormalizedResponsibilityBatch(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000312"),
                new CheckpointKey(
                        "SRC-P0-RESPONSIBILITY-001",
                        "responsibility-authority",
                        "sandbox-0",
                        "responsibility"),
                "RESPONSIBILITY-BATCH-1.0.0",
                contractVersion,
                7,
                6,
                7,
                Map.of("identity-authority|sandbox-0", 42L),
                NOW.minusSeconds(60),
                NOW,
                TRACE,
                "b".repeat(64),
                "c".repeat(64),
                true,
                new byte[] {1},
                new byte[] {2},
                new byte[] {3},
                "config://test/responsibility-inbox",
                "k1",
                List.of(relation));
    }

    private static IdentitySyncResult appliedResult() {
        return new IdentitySyncResult(
                IdentitySyncOutcome.APPLIED,
                "SYNC_APPLIED",
                7,
                7,
                1);
    }

    private static List<ResponsibilityScopeProjectionUpdate> scopeUpdates(
            NormalizedResponsibilityBatch batch) {
        var relation = batch.relations().getFirst();
        return List.of(new ResponsibilityScopeProjectionUpdate(
                relation.studentSourceReference().equivalenceDomain(),
                batch.relations(),
                ResponsibilityRecipientDecision.invalid(
                        ResponsibilityRecipientReason.ZERO_RECIPIENT),
                List.of(new ResponsibilityRecipientEvidence(
                        relation,
                        null,
                        null,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false))));
    }

    private static NormalizedIdentityBatch activeAccountBatch() {
        String externalDigest = "1".repeat(64);
        var interval = new EffectiveInterval(NOW.minusSeconds(60), null);
        var account = new AuthoritativeAccount(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000321"),
                "SRC-P0-RESPONSIBILITY-001",
                externalDigest,
                "actor_v1_k1_" + "2".repeat(64),
                AuthoritativeStatus.ACTIVE,
                interval,
                8,
                3);
        var sourceFact = new IdentitySourceFact(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000322"),
                IdentityRecordKind.ACCOUNT,
                externalDigest,
                8,
                interval,
                "3".repeat(64),
                3);
        return new NormalizedIdentityBatch(
                UUID.fromString(
                        "019c0000-0000-7000-8000-000000000323"),
                new CheckpointKey(
                        "SRC-P0-RESPONSIBILITY-001",
                        "identity-authority",
                        "sandbox-0",
                        "identity-org"),
                "IDENTITY-AUTHORITY-BATCH-1.0.0",
                8,
                7,
                8,
                NOW.minusSeconds(60),
                NOW,
                TRACE,
                "IDENTITY-ROLE-MAPPING-1.0.0",
                "4".repeat(64),
                "5".repeat(64),
                "6".repeat(64),
                true,
                new byte[] {1},
                new byte[] {2},
                new byte[] {3},
                "config://test/identity-inbox",
                "k1",
                List.of(account),
                List.of(),
                List.of(),
                List.of(sourceFact));
    }

    private static final class FakeStore
            implements AccessInvalidationStorePort,
                    AccessInvalidationImpactJobPort,
                    AccessInvalidationExpiryPort {
        private final List<AccessInvalidationAppendCommand> commands =
                new ArrayList<>();
        private final List<cn.edu.suda.scholarsense.identityaccess.domain
                        .AccessInvalidationCause>
                causes = new ArrayList<>();
        private final List<ExpiryBinding> expiries = new ArrayList<>();

        @Override
        public cn.edu.suda.scholarsense.identityaccess.domain
                        .AccessInvalidationFact
                append(AccessInvalidationAppendCommand command) {
            commands.add(command);
            return command.fact();
        }

        @Override
        public Optional<AccessInvalidationLineageHead> head(
                String lineageId) {
            return Optional.empty();
        }

        @Override
        public Optional<cn.edu.suda.scholarsense.identityaccess.domain
                        .AccessInvalidationFact>
                find(UUID eventId) {
            return commands.stream()
                    .map(AccessInvalidationAppendCommand::fact)
                    .filter(fact -> fact.eventId().equals(eventId))
                    .findFirst();
        }

        @Override
        public Optional<cn.edu.suda.scholarsense.identityaccess.domain
                        .AccessInvalidationFact>
                latest(String lineageId) {
            return commands.stream()
                    .map(AccessInvalidationAppendCommand::fact)
                    .filter(fact -> fact.lineageId().value()
                            .equals(lineageId))
                    .reduce((first, second) -> second);
        }

        @Override
        public long fencingToken(String lineageId) {
            return 0;
        }

        @Override
        public void enqueue(
                UUID jobId,
                cn.edu.suda.scholarsense.identityaccess.domain
                                .AccessInvalidationCause
                        cause,
                Instant retainUntil) {
            causes.add(cause);
        }

        @Override
        public cn.edu.suda.scholarsense.identityaccess.domain
                        .AccessInvalidationJob
                enqueue(
                        UUID jobId,
                        AccessInvalidationLineageId lineageId,
                        Instant effectiveTo,
                        String traceId) {
            throw new AssertionError(
                    "publisher must use the version-bound expiry API");
        }

        @Override
        public cn.edu.suda.scholarsense.identityaccess.domain
                        .AccessInvalidationJob
                enqueue(
                        UUID jobId,
                        AccessInvalidationLineageId lineageId,
                        UUID scheduledEventId,
                        long scheduledAggregateVersion,
                        Instant effectiveTo,
                        AccessInvalidationReason reasonCode,
                        String traceId,
                        Instant scheduledAt) {
            expiries.add(new ExpiryBinding(
                    scheduledEventId,
                    scheduledAggregateVersion,
                    effectiveTo,
                    reasonCode,
                    scheduledAt));
            return cn.edu.suda.scholarsense.identityaccess.domain
                    .AccessInvalidationJob.pending(
                            jobId,
                            cn.edu.suda.scholarsense.identityaccess.domain
                                    .AccessInvalidationJobKind.EXPIRY,
                            lineageId,
                            effectiveTo,
                            traceId);
        }
    }

    private record ExpiryBinding(
            UUID scheduledEventId,
            long scheduledAggregateVersion,
            Instant effectiveTo,
            AccessInvalidationReason reasonCode,
            Instant scheduledAt) {}
}
