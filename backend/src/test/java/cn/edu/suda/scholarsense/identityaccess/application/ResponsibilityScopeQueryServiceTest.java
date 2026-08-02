package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeFreshness;
import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeQuery;
import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeValidity;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.ResponsibilityScopeQueryAdapter;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResponsibilityScopeQueryServiceTest {
    private static final Instant NOW =
            Instant.parse("2026-07-30T00:00:00Z");
    private static final String STUDENT = "a".repeat(64);
    private static final UUID ACCOUNT =
            UUID.fromString("019c1234-0000-7000-8000-000000000701");
    private static final UUID COLLEGE =
            UUID.fromString("019c1234-0000-7000-8000-000000000702");
    private static final AccessInvalidationLineageId LINEAGE =
            new AccessInvalidationLineageId(
                    "lin_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "responsibility-authority",
            "sandbox-0",
            "responsibility");

    @Test
    void validScopeReturnsControlledRecipientButNeverAuthorization() {
        AuthoritativeResponsibilityRelation relation =
                relation("b", 7);
        var repository = new FakeRepository(List.of(relation));
        var service = service(
                repository,
                (relations, now) -> relations.stream()
                        .map(value -> new ResponsibilityRecipientEvidence(
                                value,
                                ACCOUNT,
                                COLLEGE,
                                true,
                                true,
                                true,
                                true))
                        .toList());

        var view = service.query(
                new ResponsibilityScopeQuery(STUDENT, NOW));

        assertEquals(ResponsibilityScopeValidity.VALID, view.validity());
        assertEquals(ResponsibilityScopeFreshness.FRESH, view.freshness());
        assertEquals(ACCOUNT, view.counselorAccountId());
        assertEquals(COLLEGE, view.collegeOrganizationId());
        assertEquals("RESPONSIBILITY_VALID", view.reasonCode());
        assertEquals(7, view.sourceWatermark());
    }

    @Test
    void multiplePrimaryAndMissingIdentityFailClosedWithoutRecipientLeak() {
        List<AuthoritativeResponsibilityRelation> relations = List.of(
                relation("b", 7),
                relation("c", 8));
        var multiple = service(
                new FakeRepository(relations),
                (values, now) -> values.stream()
                        .map(value -> new ResponsibilityRecipientEvidence(
                                value,
                                ACCOUNT,
                                COLLEGE,
                                true,
                                true,
                                true,
                                true))
                        .toList())
                .query(new ResponsibilityScopeQuery(STUDENT, NOW));
        assertEquals(
                ResponsibilityScopeValidity.INVALID,
                multiple.validity());
        assertEquals(
                "RESPONSIBILITY_MULTIPLE_RECIPIENTS",
                multiple.reasonCode());
        assertNull(multiple.counselorAccountId());

        var missing = service(
                new FakeRepository(List.of(relation("b", 7))),
                (values, now) -> values.stream()
                        .map(value -> new ResponsibilityRecipientEvidence(
                                value,
                                null,
                                COLLEGE,
                                false,
                                false,
                                false,
                                true,
                                true,
                                false))
                        .toList())
                .query(new ResponsibilityScopeQuery(STUDENT, NOW));
        assertEquals(
                ResponsibilityScopeValidity.INVALID,
                missing.validity());
        assertEquals(
                "RESPONSIBILITY_INACTIVE_RECIPIENT",
                missing.reasonCode());
        assertNull(missing.collegeOrganizationId());
    }

    @Test
    void staleCheckpointOrEvidenceFailureIsDependencyUnavailable() {
        var staleRepository =
                new FakeRepository(List.of(relation("b", 7)));
        staleRepository.checkpoint = new IdentityCheckpoint(
                KEY,
                7,
                7,
                7,
                NOW.minusSeconds(30),
                IdentitySourceHealth.DEGRADED,
                IdentityProjectionFreshness.STALE);
        var stale = service(
                staleRepository,
                (relations, now) -> {
                    throw new AssertionError(
                            "stale checkpoint must fail before identity lookup");
                })
                .query(new ResponsibilityScopeQuery(STUDENT, NOW));
        assertEquals(
                ResponsibilityScopeValidity.DEPENDENCY_UNAVAILABLE,
                stale.validity());
        assertEquals(ResponsibilityScopeFreshness.UNKNOWN, stale.freshness());
        assertNull(stale.counselorAccountId());

        var failed = service(
                new FakeRepository(List.of(relation("b", 7))),
                (relations, now) -> {
                    throw new IllegalStateException("database unavailable");
                })
                .query(new ResponsibilityScopeQuery(STUDENT, NOW));
        assertEquals(
                ResponsibilityScopeValidity.DEPENDENCY_UNAVAILABLE,
                failed.validity());
        assertNull(failed.counselorAccountId());
    }

    @Test
    void appliedInvalidationFenceCanOnlyTightenCurrentScope() {
        var repository = new FakeRepository(List.of(relation("b", 7)));
        var service = new ResponsibilityScopeQueryAdapter(
                repository,
                (relations, now) -> relations.stream()
                        .map(value -> new ResponsibilityRecipientEvidence(
                                value,
                                ACCOUNT,
                                COLLEGE,
                                true,
                                true,
                                true,
                                true))
                        .toList(),
                ResponsibilityScopeQueryServiceTest::trustedNow,
                KEY,
                lineage -> LINEAGE.equals(lineage));

        var view = service.query(
                new ResponsibilityScopeQuery(STUDENT, NOW));

        assertEquals(
                ResponsibilityScopeValidity.INVALID,
                view.validity());
        assertNull(view.counselorAccountId());
        assertEquals(
                "RESPONSIBILITY_RECONCILIATION_DIFFERENCES",
                view.reasonCode());
    }

    @Test
    void cascadeReadBackUsesOnlyExactLineageAccountAndStudent() {
        AccessInvalidationLineageId unrelatedLineage =
                new AccessInvalidationLineageId(
                        "lin_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        AuthoritativeResponsibilityRelation target =
                relation("b", 7, LINEAGE);
        AuthoritativeResponsibilityRelation unrelated =
                relation("c", 8, unrelatedLineage);
        var repository = new FakeRepository(
                List.of(target, unrelated));
        var service = new ResponsibilityScopeQueryAdapter(
                repository,
                (relations, now) -> {
                    assertEquals(List.of(target), relations);
                    return List.of(new ResponsibilityRecipientEvidence(
                            target,
                            ACCOUNT,
                            COLLEGE,
                            true,
                            true,
                            true,
                            true));
                },
                ResponsibilityScopeQueryServiceTest::trustedNow,
                KEY,
                unrelatedLineage::equals);

        ResponsibilityScopeReadBack readBack = service.readBack(
                LINEAGE,
                ACCOUNT,
                target.studentSourceReference().equivalenceDomain(),
                NOW);

        assertEquals(
                ResponsibilityRecipientValidity.VALID,
                readBack.validity());
        assertEquals(7, readBack.sourceVersion());
        assertEquals(7, readBack.sourceWatermark());
        assertEquals(7, readBack.aggregateVersion());
    }

    private static ResponsibilityScopeQueryAdapter service(
            ResponsibilitySyncRepository repository,
            ResponsibilityRecipientEvidencePort evidence) {
        return new ResponsibilityScopeQueryAdapter(
                repository,
                evidence,
                ResponsibilityScopeQueryServiceTest::trustedNow,
                KEY);
    }

    private static AuthoritativeResponsibilityRelation relation(
            String suffix, long version) {
        return relation(suffix, version, LINEAGE);
    }

    private static AuthoritativeResponsibilityRelation relation(
            String suffix,
            long version,
            AccessInvalidationLineageId lineage) {
        return new AuthoritativeResponsibilityRelation(
                UUID.fromString(
                        "019c1234-0000-7000-8000-00000000070"
                                + (version - 6)),
                KEY.sourceId(),
                "rtok_" + suffix.repeat(40),
                new ResponsibilityStudentSourceReference(
                        "RESPONSIBILITY-STUDENT-REF",
                        "resp-student-v2",
                        "stok_" + "d".repeat(40),
                        STUDENT,
                        "e".repeat(64)),
                "f".repeat(64),
                "1".repeat(64),
                ResponsibilityType.PRIMARY,
                ResponsibilityStatus.ACTIVE,
                new EffectiveInterval(NOW.minusSeconds(60), null),
                version,
                version,
                version,
                version,
                "2".repeat(64),
                AccessInvalidationChangeKind.CORRECTED,
                AccessInvalidationReason.SOURCE_CORRECTION,
                NOW,
                lineage,
                null);
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
        private final List<AuthoritativeResponsibilityRelation> relations;
        private IdentityCheckpoint checkpoint =
                new IdentityCheckpoint(
                        KEY,
                        7,
                        7,
                        7,
                        NOW.minusSeconds(30),
                        IdentitySourceHealth.HEALTHY,
                        IdentityProjectionFreshness.FRESH);

        private FakeRepository(
                List<AuthoritativeResponsibilityRelation> relations) {
            this.relations = new ArrayList<>(relations);
        }

        @Override
        public Optional<IdentityCheckpoint> checkpoint(
                CheckpointKey key) {
            return Optional.of(checkpoint);
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
        public long identityOrgWatermark(
                String feedId, String partitionId) {
            return 42;
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
                        String studentSourceRefDigest,
                        Instant serverNow) {
            return List.copyOf(relations);
        }

        @Override
        public Optional<AuthoritativeResponsibilityRelation>
                currentCascadeScope(
                        CheckpointKey key,
                        AccessInvalidationLineageId accessLineageId,
                        UUID counselorAccountId,
                        String studentEquivalenceDigest,
                        Instant serverNow) {
            if (!ACCOUNT.equals(counselorAccountId)) {
                return Optional.empty();
            }
            return relations.stream()
                    .filter(relation -> accessLineageId.equals(
                            relation.lineageId()))
                    .filter(relation -> studentEquivalenceDigest.equals(
                            relation.studentSourceReference()
                                    .equivalenceDomain()))
                    .findFirst();
        }

        @Override
        public void reject(IdentitySyncRejection rejection) {}

        @Override
        public boolean leaseIsCurrent(IdentityLease lease) {
            return true;
        }
    }
}
