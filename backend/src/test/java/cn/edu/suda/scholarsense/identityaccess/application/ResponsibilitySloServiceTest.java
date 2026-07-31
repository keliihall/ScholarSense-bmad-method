package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientValidity;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStudentSourceReference;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityType;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResponsibilitySloServiceTest {
    private static final Instant SOURCE_VISIBLE =
            Instant.parse("2026-07-30T00:00:00Z");
    private static final Instant APPLIED =
            SOURCE_VISIBLE.plusSeconds(300);
    private static final Instant BOUNDARY =
            SOURCE_VISIBLE.plusSeconds(900);
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "responsibility-authority",
            "sandbox-0",
            "responsibility");

    @Test
    void exactFifteenMinutePublicReadBackMeetsSlo() {
        var store = new FakeEvidence();
        var service = service(
                store,
                new ResponsibilityScopeReadBack(
                        ResponsibilityRecipientValidity.VALID,
                        "RESPONSIBILITY_VALID",
                        7,
                        7,
                        7,
                        BOUNDARY));

        service.record(batch(), APPLIED, result());

        assertEquals(1, store.appended.size());
        assertTrue(store.appended.getFirst()
                .withinFifteenMinutes());
        assertEquals(BOUNDARY, store.appended.getFirst()
                .authorizationEffectiveAt());
    }

    @Test
    void invalidationIsSuccessfulWhenItsNewVersionIsVisible() {
        var store = new FakeEvidence();
        var service = service(
                store,
                new ResponsibilityScopeReadBack(
                        ResponsibilityRecipientValidity.INVALID,
                        "RESPONSIBILITY_ZERO_RECIPIENT",
                        7,
                        7,
                        7,
                        BOUNDARY));

        service.record(batch(), APPLIED, result());

        assertTrue(store.appended.getFirst()
                .withinFifteenMinutes());
    }

    @Test
    void emptyReadBackAndEvidenceWriteFailureStayInDenominator() {
        var emptyStore = new FakeEvidence();
        service(
                emptyStore,
                new ResponsibilityScopeReadBack(
                        ResponsibilityRecipientValidity.INVALID,
                        "RESPONSIBILITY_ZERO_RECIPIENT",
                        0,
                        0,
                        0,
                        APPLIED))
                .record(batch(), APPLIED, result());
        assertFalse(emptyStore.appended.getFirst()
                .withinFifteenMinutes());
        assertEquals(
                "RESPONSIBILITY_SCOPE_READBACK_EMPTY",
                emptyStore.appended.getFirst().lateReasonCode());

        var failingStore = new FakeEvidence();
        failingStore.failAppend = true;
        service(
                failingStore,
                new ResponsibilityScopeReadBack(
                        ResponsibilityRecipientValidity.VALID,
                        "RESPONSIBILITY_VALID",
                        7,
                        7,
                        7,
                        BOUNDARY))
                .record(batch(), APPLIED, result());
        assertEquals(1, failingStore.compensated.size());
        assertFalse(failingStore.compensated.getFirst()
                .withinFifteenMinutes());
        assertEquals(
                "RESPONSIBILITY_SLO_EVIDENCE_WRITE_FAILED",
                failingStore.compensated.getFirst()
                        .lateReasonCode());
    }

    @Test
    void rollingThirtyDayTargetUsesTheRealNumeratorAndDenominator() {
        assertTrue(new ResponsibilitySloWindow(99, 100)
                .meetsTarget());
        assertTrue(new ResponsibilitySloWindow(990, 1000)
                .meetsTarget());
        assertFalse(new ResponsibilitySloWindow(989, 1000)
                .meetsTarget());
        assertFalse(new ResponsibilitySloWindow(0, 0)
                .meetsTarget());
    }

    @Test
    void replayRepairsMissingEvidenceAndDoublePersistenceFailureEscalates() {
        var replayStore = new FakeEvidence();
        service(
                        replayStore,
                        new ResponsibilityScopeReadBack(
                                ResponsibilityRecipientValidity.VALID,
                                "RESPONSIBILITY_VALID",
                                7,
                                7,
                                7,
                                BOUNDARY))
                .record(
                        batch(),
                        APPLIED,
                        new IdentitySyncResult(
                                IdentitySyncOutcome.REPLAYED,
                                "RESPONSIBILITY_SYNC_REPLAYED",
                                7,
                                7,
                                7));
        assertEquals(1, replayStore.appended.size());
        assertEquals(
                "d".repeat(64),
                replayStore.appended.getFirst()
                        .studentSourceRefDigest());

        var unavailable = new FakeEvidence();
        unavailable.failAppend = true;
        unavailable.failCompensate = true;
        IdentitySyncException failure = assertThrows(
                IdentitySyncException.class,
                () -> service(
                                unavailable,
                                new ResponsibilityScopeReadBack(
                                        ResponsibilityRecipientValidity
                                                .VALID,
                                        "RESPONSIBILITY_VALID",
                                        7,
                                        7,
                                        7,
                                        BOUNDARY))
                        .record(batch(), APPLIED, result()));
        assertEquals(
                "RESPONSIBILITY_SLO_COMPENSATION_PERSISTENCE_UNAVAILABLE",
                failure.code());
    }

    private static ResponsibilitySloService service(
            FakeEvidence store,
            ResponsibilityScopeReadBack readBack) {
        return new ResponsibilitySloService(
                (student, now) -> readBack,
                store,
                ignored -> {},
                () -> trusted(APPLIED));
    }

    private static IdentitySyncResult result() {
        return new IdentitySyncResult(
                IdentitySyncOutcome.APPLIED,
                "RESPONSIBILITY_SYNC_APPLIED",
                7,
                7,
                7);
    }

    private static NormalizedResponsibilityBatch batch() {
        var relation = new AuthoritativeResponsibilityRelation(
                UUID.fromString(
                        "019c1234-0000-7000-8000-000000000701"),
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
                new EffectiveInterval(
                        SOURCE_VISIBLE.minusSeconds(60), null),
                7,
                7,
                1,
                7,
                "a".repeat(64));
        return new NormalizedResponsibilityBatch(
                UUID.fromString(
                        "019c1234-0000-7000-8000-000000000702"),
                KEY,
                "RESPONSIBILITY-BATCH-1.0.0",
                "RESPONSIBILITY-AUTHORITY-1.0.0",
                7,
                6,
                7,
                Map.of("identity-authority|sandbox-0", 42L),
                SOURCE_VISIBLE,
                APPLIED,
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

    private static TrustedTime trusted(Instant instant) {
        return new TrustedTime(
                instant,
                new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        instant.minusSeconds(10),
                        instant.plusSeconds(50),
                        "evidence://signed/clock/campus-ntp-a.json"));
    }

    private static final class FakeEvidence
            implements ResponsibilitySloEvidencePort {
        private final List<ResponsibilitySloEvidence> appended =
                new ArrayList<>();
        private final List<ResponsibilitySloEvidence> compensated =
                new ArrayList<>();
        private boolean failAppend;
        private boolean failCompensate;

        @Override
        public void append(ResponsibilitySloEvidence evidence) {
            if (failAppend) {
                throw new IllegalStateException("unavailable");
            }
            appended.add(evidence);
        }

        @Override
        public void compensate(
                ResponsibilitySloEvidence evidence,
                String reasonCode) {
            if (failCompensate) {
                throw new IllegalStateException("unavailable");
            }
            assertEquals(
                    "RESPONSIBILITY_SLO_EVIDENCE_WRITE_FAILED",
                    reasonCode);
            compensated.add(evidence);
        }

        @Override
        public Optional<ResponsibilitySloEvidence>
                nextCompensation() {
            return Optional.empty();
        }
    }
}
