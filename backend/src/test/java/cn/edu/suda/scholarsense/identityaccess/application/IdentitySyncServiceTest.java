package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeAccount;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.identityaccess.domain.EmploymentRoleBinding;
import cn.edu.suda.scholarsense.identityaccess.domain.OrganizationNode;
import cn.edu.suda.scholarsense.identityaccess.domain.OrganizationType;
import cn.edu.suda.scholarsense.identityaccess.domain.TargetRole;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdentitySyncServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-24T00:02:00Z");
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001", "identity-authority", "sandbox-0", "identity-org");

    @Test
    void atomicallyAppliesContinuousBatchAuditsAndRecordsReadBackSlo() {
        var repository = new FakeRepository(checkpoint(6, 6, 2));
        var transactions = new AtomicInteger();
        var audits = new ArrayList<IdentitySyncAuditEvent>();
        var observations = new ArrayList<IdentitySyncObservation>();
        var slo = new ArrayList<IdentitySloEvidence>();
        NormalizedIdentityBatch batch = batch(6, 7, 7, digest("batch-7"));
        var service = new IdentitySyncService(
                repository,
                (_key, from, to, traceId) -> {},
                new IdentitySyncTransactionPort() {
                    @Override
                    public <T> T execute(java.util.function.Supplier<T> work) {
                        transactions.incrementAndGet();
                        return work.get();
                    }
                },
                audits::add,
                observations::add,
                actor -> Optional.of(context(batch)),
                slo::add,
                IdentitySyncServiceTest::trustedNow);

        IdentitySyncResult result = service.process(batch, lease(3));

        assertEquals(IdentitySyncOutcome.APPLIED, result.outcome());
        assertEquals(1, repository.applied);
        assertEquals(1, transactions.get());
        assertEquals("identity.sync.applied", audits.getFirst().action());
        assertTrue(slo.getFirst().withinFifteenMinutes());
        assertEquals(NOW, slo.getFirst().authorizationEffectiveAt());
        assertEquals(3, slo.size());
        assertEquals(
                0,
                slo.stream()
                        .filter(evidence ->
                                "IDENTITY_AUTHORIZATION_READBACK_EMPTY".equals(
                                        evidence.lateReasonCode()))
                        .count());
        assertFalse(observations.getFirst().labels().containsKey("externalId"));
    }

    @Test
    void readBackVersionMismatchAndEvidenceWriteFailureStayInTheSloDenominator() {
        var repository = new FakeRepository(checkpoint(6, 6, 2));
        var observations = new ArrayList<IdentitySyncObservation>();
        var compensation = new ArrayList<IdentitySloEvidence>();
        NormalizedIdentityBatch batch = batch(6, 7, 7, digest("slo-compensation"));
        var service = new IdentitySyncService(
                repository,
                (_key, from, to, traceId) -> {},
                directTransaction(),
                ignored -> {},
                observations::add,
                actor -> Optional.of(new AuthorizationEffectiveContext(
                        batch.accounts().getFirst().accountId(),
                        6,
                        6,
                        AuthorizationFreshness.FRESH)),
                new IdentitySloEvidencePort() {
                    @Override
                    public void append(IdentitySloEvidence evidence) {
                        throw new IllegalStateException("evidence store unavailable");
                    }

                    @Override
                    public void compensate(
                            IdentitySloEvidence evidence, String reasonCode) {
                        compensation.add(evidence);
                    }
                },
                IdentitySyncServiceTest::trustedNow);

        IdentitySyncResult result = service.process(batch, lease(3));

        assertEquals(IdentitySyncOutcome.APPLIED, result.outcome());
        assertEquals(3, compensation.size());
        assertTrue(observations.stream().anyMatch(observation ->
                "identity_sync_slo_probe_total".equals(observation.metric())
                        && "compensation_required".equals(
                                observation.labels().get("outcome"))));
    }

    @Test
    void organizationSloEvidenceCoversEveryAffectedAccountWithoutSyntheticIds() {
        var repository = new FakeRepository(checkpoint(6, 6, 2));
        repository.organizationSubjectBindings =
                List.of("actor_v1_k1_" + "b".repeat(64));
        var evidence = new ArrayList<IdentitySloEvidence>();
        NormalizedIdentityBatch batch =
                batch(6, 7, 7, digest("all-affected-accounts"));
        UUID secondAccount = uuid("304");
        var service = new IdentitySyncService(
                repository,
                (_key, from, to, traceId) -> {},
                directTransaction(),
                ignored -> {},
                ignored -> {},
                actor -> Optional.of(new AuthorizationEffectiveContext(
                        actor.endsWith("b".repeat(64))
                                ? secondAccount
                                : batch.accounts().getFirst().accountId(),
                        7,
                        7,
                        AuthorizationFreshness.FRESH)),
                evidence::add,
                IdentitySyncServiceTest::trustedNow);

        service.process(batch, lease(3));

        List<IdentitySloEvidence> organizationEvidence = evidence.stream()
                .filter(value ->
                        value.recordKind() == IdentityRecordKind.ORGANIZATION)
                .toList();
        assertEquals(2, organizationEvidence.size());
        assertEquals(
                2,
                organizationEvidence.stream()
                        .map(IdentitySloEvidence::accountId)
                        .distinct()
                        .count());
    }

    @Test
    void emptyReadBackUsesNullAccountInsteadOfAnEventId() {
        var repository = new FakeRepository(checkpoint(6, 6, 2));
        var evidence = new ArrayList<IdentitySloEvidence>();
        NormalizedIdentityBatch batch =
                batch(6, 7, 7, digest("empty-readback"));
        var service = new IdentitySyncService(
                repository,
                (_key, from, to, traceId) -> {},
                directTransaction(),
                ignored -> {},
                ignored -> {},
                actor -> Optional.empty(),
                evidence::add,
                IdentitySyncServiceTest::trustedNow);

        service.process(batch, lease(3));

        assertEquals(3, evidence.size());
        assertTrue(evidence.stream().allMatch(value ->
                value.accountId() == null
                        && "IDENTITY_AUTHORIZATION_READBACK_EMPTY".equals(
                                value.lateReasonCode())));
    }

    @Test
    void duplicateBatchIsIdempotentAndDifferentPayloadIsStableConflict() {
        NormalizedIdentityBatch batch = batch(6, 7, 7, digest("batch-7"));
        var replayRepository = new FakeRepository(checkpoint(7, 7, 3));
        replayRepository.digest = batch.envelopeDigest();
        var service = service(replayRepository);

        assertEquals(IdentitySyncOutcome.REPLAYED, service.process(batch, lease(4)).outcome());
        assertEquals(0, replayRepository.applied);

        replayRepository.digest = digest("different");
        IdentitySyncException conflict = assertThrows(
                IdentitySyncException.class, () -> service.process(batch, lease(4)));
        assertEquals("IDENTITY_SOURCE_PAYLOAD_CONFLICT", conflict.code());
        assertEquals(1, replayRepository.rejected);
    }

    @Test
    void gapRequestsExactReplayAndNeverAdvancesProjectionOrCheckpoint() {
        var repository = new FakeRepository(checkpoint(4, 4, 1));
        var replay = new ArrayList<String>();
        var service = new IdentitySyncService(
                repository,
                (key, from, to, traceId) -> replay.add(from + ":" + to),
                directTransaction(),
                ignored -> {},
                ignored -> {},
                actor -> Optional.empty(),
                ignored -> {},
                IdentitySyncServiceTest::trustedNow);

        IdentitySyncException gap = assertThrows(
                IdentitySyncException.class,
                () -> service.process(batch(6, 7, 7, digest("batch-7")), lease(2)));

        assertEquals("IDENTITY_SOURCE_CURSOR_GAP", gap.code());
        assertEquals(List.of("5:6"), replay);
        assertEquals(0, repository.applied);
        assertEquals(1, repository.rejected);
    }

    @Test
    void staleFencingTokenAndInvalidOrganizationAreRejectedBeforeWrites() {
        var repository = new FakeRepository(checkpoint(6, 6, 2));
        var service = service(repository);
        IdentitySyncException stale = assertThrows(
                IdentitySyncException.class,
                () -> service.process(batch(6, 7, 7, digest("batch-7")), lease(2, NOW.minusSeconds(1))));
        assertEquals("IDENTITY_SYNC_FENCING_STALE", stale.code());
        assertEquals(0, repository.applied);

        NormalizedIdentityBatch valid = batch(6, 7, 7, digest("batch-8"));
        OrganizationNode orphan = new OrganizationNode(
                valid.organizations().getFirst().organizationId(),
                "SRC-P0-RESPONSIBILITY-001",
                digest("org"),
                digest("missing"),
                "缺失上级组织",
                OrganizationType.COLLEGE,
                AuthoritativeStatus.ACTIVE,
                new EffectiveInterval(NOW.minusSeconds(120), null),
                7,
                3);
        NormalizedIdentityBatch invalid = valid.withOrganizations(List.of(orphan));
        IdentitySyncException invalidOrganization = assertThrows(
                IdentitySyncException.class, () -> service.process(invalid, lease(3)));
        assertEquals("IDENTITY_ORGANIZATION_ORPHAN", invalidOrganization.code());
        assertEquals(0, repository.applied);
        assertEquals(1, repository.rejected);
    }

    @Test
    void leaseThatExpiredInTheDatabaseCannotWriteRejectionOrAudit() {
        var repository = new FakeRepository(checkpoint(6, 6, 2));
        repository.leaseCurrent = false;
        var audits = new ArrayList<IdentitySyncAuditEvent>();
        var service = new IdentitySyncService(
                repository,
                (_key, from, to, traceId) -> {},
                directTransaction(),
                audits::add,
                ignored -> {},
                actor -> Optional.empty(),
                ignored -> {},
                IdentitySyncServiceTest::trustedNow);

        IdentitySyncException stale = assertThrows(
                IdentitySyncException.class,
                () -> service.process(
                        batch(6, 7, 7, digest("database-expired")),
                        lease(3, NOW.plusSeconds(60))));

        assertEquals("IDENTITY_SYNC_FENCING_STALE", stale.code());
        assertEquals(0, repository.rejected);
        assertTrue(audits.isEmpty());
    }

    @Test
    void duplicateSubjectBindingIsRejectedWithoutApplyingTheBatch() {
        var repository = new FakeRepository(checkpoint(6, 6, 2));
        var service = service(repository);
        NormalizedIdentityBatch valid = batch(6, 7, 7, digest("batch-subject-conflict"));
        AuthoritativeAccount first = valid.accounts().getFirst();
        AuthoritativeAccount conflict = new AuthoritativeAccount(
                uuid("304"),
                KEY.sourceId(),
                digest("account-conflict"),
                first.subjectBindingToken(),
                AuthoritativeStatus.ACTIVE,
                first.effectiveInterval(),
                first.sourceVersion(),
                first.aggregateVersion());
        List<IdentitySourceFact> facts = new ArrayList<>(valid.sourceFacts());
        facts.add(fact(
                "314",
                IdentityRecordKind.ACCOUNT,
                conflict.externalRefDigest(),
                conflict.sourceVersion()));
        NormalizedIdentityBatch duplicate = valid.withAccountsAndSourceFacts(
                List.of(first, conflict), facts);

        IdentitySyncException rejected = assertThrows(
                IdentitySyncException.class,
                () -> service.process(duplicate, lease(3)));

        assertEquals("IDENTITY_SUBJECT_BINDING_CONFLICT", rejected.code());
        assertEquals(0, repository.applied);
        assertEquals(1, repository.rejected);
    }

    @Test
    void incrementalOrganizationChangeCanReferenceAnUnchangedCurrentParent() {
        var repository = new FakeRepository(checkpoint(6, 6, 2));
        repository.currentOrganizations = List.of(new OrganizationNode(
                uuid("330"),
                KEY.sourceId(),
                digest("existing-parent"),
                null,
                "苏州大学",
                OrganizationType.SCHOOL,
                AuthoritativeStatus.ACTIVE,
                new EffectiveInterval(NOW.minusSeconds(3600), null),
                6,
                2));

        IdentitySyncResult result =
                service(repository).process(organizationOnlyBatch(), lease(3));

        assertEquals(IdentitySyncOutcome.APPLIED, result.outcome());
        assertEquals(1, repository.applied);
    }

    @Test
    void staleRecordAndSameVersionDifferentPayloadCannotAdvanceTheBatchCheckpoint() {
        var repository = new FakeRepository(checkpoint(6, 6, 2));
        NormalizedIdentityBatch incoming = batch(6, 7, 7, digest("incoming"));
        IdentitySourceFact accountFact = incoming.sourceFacts().getFirst();
        repository.currentRecord = new IdentityRecordState(
                8, accountFact.payloadDigest());

        IdentitySyncException stale = assertThrows(
                IdentitySyncException.class,
                () -> service(repository).process(incoming, lease(3)));
        assertEquals("IDENTITY_SOURCE_VERSION_STALE", stale.code());
        assertEquals(0, repository.applied);

        repository.currentRecord = new IdentityRecordState(
                7, digest("different-payload"));
        IdentitySyncException conflict = assertThrows(
                IdentitySyncException.class,
                () -> service(repository).process(incoming, lease(3)));
        assertEquals("IDENTITY_SOURCE_PAYLOAD_CONFLICT", conflict.code());
        assertEquals(0, repository.applied);
    }

    @Test
    void ordinaryAccountIncrementCannotRebindAnExistingAuthoritativeSubject() {
        var repository = new FakeRepository(checkpoint(6, 6, 2));
        NormalizedIdentityBatch incoming = batch(6, 7, 7, digest("rebind"));
        repository.currentSubjectBinding =
                "actor_v1_k1_" + "b".repeat(64);

        IdentitySyncException rejected = assertThrows(
                IdentitySyncException.class,
                () -> service(repository).process(incoming, lease(3)));

        assertEquals("IDENTITY_SUBJECT_REBIND_FORBIDDEN", rejected.code());
        assertEquals(0, repository.applied);
    }

    @Test
    void approvedKeyRotationDualReadsOldTokenAndWritesTheNewToken() {
        var repository = new FakeRepository(checkpoint(6, 6, 2));
        NormalizedIdentityBatch incoming = batch(6, 7, 7, digest("key-rotation"));
        AuthoritativeAccount original = incoming.accounts().getFirst();
        String previous = "actor_v1_k1_" + "b".repeat(64);
        String current = "actor_v1_k2_" + "c".repeat(64);
        repository.currentSubjectBinding = previous;
        AuthoritativeAccount rotated = new AuthoritativeAccount(
                original.accountId(),
                original.sourceId(),
                original.externalRefDigest(),
                current,
                List.of(current, previous),
                original.status(),
                original.effectiveInterval(),
                original.sourceVersion(),
                original.aggregateVersion());

        IdentitySyncResult result = service(repository).process(
                incoming.withAccounts(List.of(rotated)), lease(3));

        assertEquals(IdentitySyncOutcome.APPLIED, result.outcome());
        assertEquals(1, repository.applied);
        assertEquals(current, rotated.subjectBindingToken());
    }

    private static IdentitySyncService service(FakeRepository repository) {
        return new IdentitySyncService(
                repository,
                (key, from, to, traceId) -> {},
                directTransaction(),
                ignored -> {},
                ignored -> {},
                actor -> Optional.empty(),
                ignored -> {},
                IdentitySyncServiceTest::trustedNow);
    }

    private static IdentitySyncTransactionPort directTransaction() {
        return new IdentitySyncTransactionPort() {
            @Override
            public <T> T execute(java.util.function.Supplier<T> work) {
                return work.get();
            }
        };
    }

    private static NormalizedIdentityBatch batch(
            long fromWatermark, long toWatermark, long sourceVersion, String envelopeDigest) {
        UUID accountId = uuid("301");
        UUID organizationId = uuid("302");
        UUID bindingId = uuid("303");
        Instant effectiveFrom = NOW.minusSeconds(120);
        var interval = new EffectiveInterval(effectiveFrom, null);
        var account = new AuthoritativeAccount(
                accountId, KEY.sourceId(), digest("account"),
                "actor_v1_k1_" + "a".repeat(64), AuthoritativeStatus.ACTIVE,
                interval, sourceVersion, 3);
        var organization = new OrganizationNode(
                organizationId, KEY.sourceId(), digest("org"), null,
                "苏州大学",
                OrganizationType.SCHOOL, AuthoritativeStatus.ACTIVE,
                interval, sourceVersion, 3);
        var role = new EmploymentRoleBinding(
                bindingId, accountId, organizationId, digest("role"),
                "SANDBOX_STUDENT_AFFAIRS", TargetRole.R3_STUDENT_AFFAIRS,
                "IDENTITY-ROLE-MAPPING-1.0.0", AuthoritativeStatus.ACTIVE,
                interval, sourceVersion, 3);
        return new NormalizedIdentityBatch(
                uuid("310"), KEY, "IDENTITY-AUTHORITY-BATCH-1.0.0",
                sourceVersion, fromWatermark, toWatermark,
                effectiveFrom, NOW.minusSeconds(30),
                "0123456789abcdef0123456789abcdef",
                "IDENTITY-ROLE-MAPPING-1.0.0",
                digest("mapping"), envelopeDigest, digest("signature"), true,
                new byte[] {1, 2, 3}, new byte[] {4, 5}, new byte[] {6, 7},
                "config://test/identity-inbox-key", "k1",
                List.of(account), List.of(organization), List.of(role),
                List.of(
                        fact("311", IdentityRecordKind.ACCOUNT, account.externalRefDigest(), sourceVersion),
                        fact("312", IdentityRecordKind.ORGANIZATION, organization.externalRefDigest(), sourceVersion),
                        fact("313", IdentityRecordKind.EMPLOYMENT_ROLE, role.externalRefDigest(), sourceVersion)));
    }

    private static IdentitySourceFact fact(
            String suffix, IdentityRecordKind kind, String externalRefDigest, long sourceVersion) {
        return new IdentitySourceFact(
                uuid(suffix), kind, externalRefDigest, sourceVersion,
                new EffectiveInterval(NOW.minusSeconds(120), null),
                digest("payload-" + suffix), 3);
    }

    private static NormalizedIdentityBatch organizationOnlyBatch() {
        var child = new OrganizationNode(
                uuid("331"),
                KEY.sourceId(),
                digest("child"),
                digest("existing-parent"),
                "计算机学院",
                OrganizationType.COLLEGE,
                AuthoritativeStatus.ACTIVE,
                new EffectiveInterval(NOW.minusSeconds(120), null),
                7,
                3);
        return new NormalizedIdentityBatch(
                uuid("332"),
                KEY,
                "IDENTITY-AUTHORITY-BATCH-1.0.0",
                7,
                6,
                7,
                NOW.minusSeconds(120),
                NOW.minusSeconds(30),
                "0123456789abcdef0123456789abcdef",
                "IDENTITY-ROLE-MAPPING-1.0.0",
                digest("mapping"),
                digest("organization-only"),
                digest("signature"),
                true,
                new byte[] {1},
                new byte[] {2},
                new byte[] {3},
                "config://test/identity-inbox-key",
                "k1",
                List.of(),
                List.of(child),
                List.of(),
                List.of(fact(
                        "333",
                        IdentityRecordKind.ORGANIZATION,
                        child.externalRefDigest(),
                        7)));
    }

    private static AuthorizationEffectiveContext context(NormalizedIdentityBatch batch) {
        return new AuthorizationEffectiveContext(
                batch.accounts().getFirst().accountId(),
                batch.sourceVersion(),
                batch.toWatermark(),
                AuthorizationFreshness.FRESH);
    }

    private static IdentityCheckpoint checkpoint(long watermark, long sourceVersion, long aggregateVersion) {
        return new IdentityCheckpoint(
                KEY, sourceVersion, watermark, aggregateVersion,
                NOW.minusSeconds(60), IdentitySourceHealth.HEALTHY, IdentityProjectionFreshness.FRESH);
    }

    private static IdentityLease lease(long fencingToken) {
        return lease(fencingToken, NOW.plus(Duration.ofMinutes(1)));
    }

    private static IdentityLease lease(long fencingToken, Instant expiresAt) {
        return new IdentityLease(
                KEY, uuid("320"), 1, fencingToken, "worker-test",
                expiresAt.minusSeconds(60), expiresAt);
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

    private static UUID uuid(String suffix) {
        return UUID.fromString("019c1234-0000-7000-8000-000000000" + suffix);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class FakeRepository implements IdentitySyncRepository {
        private IdentityCheckpoint checkpoint;
        private String digest;
        private int applied;
        private int rejected;
        private List<OrganizationNode> currentOrganizations = List.of();
        private IdentityRecordState currentRecord;
        private String currentSubjectBinding;
        private List<String> organizationSubjectBindings = List.of();
        private boolean leaseCurrent = true;

        private FakeRepository(IdentityCheckpoint checkpoint) {
            this.checkpoint = checkpoint;
        }

        @Override
        public Optional<IdentityCheckpoint> checkpoint(CheckpointKey key) {
            return Optional.ofNullable(checkpoint);
        }

        @Override
        public Optional<String> envelopeDigest(UUID batchId) {
            return Optional.ofNullable(digest);
        }

        @Override
        public void apply(NormalizedIdentityBatch batch, IdentityLease lease, Instant appliedAt) {
            applied++;
            digest = batch.envelopeDigest();
            checkpoint = new IdentityCheckpoint(
                    batch.key(), batch.sourceVersion(), batch.toWatermark(),
                    checkpoint.aggregateVersion() + 1, appliedAt,
                    IdentitySourceHealth.HEALTHY, IdentityProjectionFreshness.FRESH);
        }

        @Override
        public void reject(IdentitySyncRejection rejection) {
            rejected++;
        }

        @Override
        public boolean leaseIsCurrent(IdentityLease lease) {
            return leaseCurrent;
        }

        @Override
        public List<OrganizationNode> currentOrganizations(CheckpointKey key) {
            return currentOrganizations;
        }

        public Optional<IdentityRecordState> currentRecord(
                CheckpointKey key,
                IdentityRecordKind kind,
                String externalRefDigest) {
            return Optional.ofNullable(currentRecord);
        }

        public Optional<String> currentSubjectBinding(
                CheckpointKey key, String externalRefDigest) {
            return Optional.ofNullable(currentSubjectBinding);
        }

        @Override
        public List<String> currentSubjectBindingsForOrganization(
                CheckpointKey key, UUID organizationId) {
            return organizationSubjectBindings;
        }
    }
}
