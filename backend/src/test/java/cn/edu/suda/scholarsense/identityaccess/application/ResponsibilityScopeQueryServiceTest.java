package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeFreshness;
import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeQuery;
import cn.edu.suda.scholarsense.identityaccess.api.ResponsibilityScopeValidity;
import cn.edu.suda.scholarsense.identityaccess.adapters.outbound.ResponsibilityScopeQueryAdapter;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientEvidence;
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
                "2".repeat(64));
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
        public void reject(IdentitySyncRejection rejection) {}

        @Override
        public boolean leaseIsCurrent(IdentityLease lease) {
            return true;
        }
    }
}
