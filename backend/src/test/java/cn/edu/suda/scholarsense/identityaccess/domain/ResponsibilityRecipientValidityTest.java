package cn.edu.suda.scholarsense.identityaccess.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResponsibilityRecipientValidityTest {
    private static final Instant START = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-08-01T00:00:00Z");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("019c1234-0000-7000-8000-000000000201");
    private static final UUID COLLEGE_ID =
            UUID.fromString("019c1234-0000-7000-8000-000000000202");

    @Test
    void intervalIsStartInclusiveAndEndExclusive() {
        var evidence = validEvidence(relation(new EffectiveInterval(START, END)));

        assertEquals(
                ResponsibilityRecipientValidity.VALID,
                ResponsibilityRecipientEvaluator.evaluate(
                                List.of(evidence), START, true, true)
                        .validity());
        assertEquals(
                ResponsibilityRecipientReason.EFFECTIVE_END_REACHED,
                ResponsibilityRecipientEvaluator.evaluate(
                                List.of(evidence), END, true, true)
                        .reason());
    }

    @Test
    void onlyOneActivePrimaryR1InMatchingActiveCollegeIsValid() {
        var evidence = validEvidence(relation(new EffectiveInterval(START, null)));
        var valid = ResponsibilityRecipientEvaluator.evaluate(
                List.of(evidence), START.plusSeconds(1), true, true);
        assertEquals(ResponsibilityRecipientValidity.VALID, valid.validity());
        assertEquals(ACCOUNT_ID, valid.counselorAccountId());

        var duplicate = ResponsibilityRecipientEvaluator.evaluate(
                List.of(evidence, evidence), START.plusSeconds(1), true, true);
        assertEquals(ResponsibilityRecipientValidity.INVALID, duplicate.validity());
        assertEquals(
                ResponsibilityRecipientReason.MULTIPLE_RECIPIENTS,
                duplicate.reason());

        var wrongRole = ResponsibilityRecipientEvaluator.evaluate(
                List.of(new ResponsibilityRecipientEvidence(
                        evidence.relation(),
                        ACCOUNT_ID,
                        COLLEGE_ID,
                        true,
                        false,
                        true,
                        true)),
                START.plusSeconds(1),
                true,
                true);
        assertEquals(ResponsibilityRecipientReason.NON_R1_RECIPIENT, wrongRole.reason());
    }

    @Test
    void unavailableIdentityOrClockFailsClosedWithoutRecipientLeakage() {
        var evidence = validEvidence(relation(new EffectiveInterval(START, null)));
        var identityUnavailable = ResponsibilityRecipientEvaluator.evaluate(
                List.of(evidence), START, false, true);
        assertEquals(
                ResponsibilityRecipientValidity.DEPENDENCY_UNAVAILABLE,
                identityUnavailable.validity());
        assertEquals(null, identityUnavailable.counselorAccountId());

        var clockUnavailable =
                ResponsibilityRecipientEvaluator.evaluate(List.of(evidence), null, true, false);
        assertEquals(
                ResponsibilityRecipientReason.CLOCK_UNAVAILABLE,
                clockUnavailable.reason());
    }

    @Test
    void studentReferenceRejectsWrongPurposeAndUnknownKeyVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResponsibilityStudentSourceReference(
                        "WRONG-PURPOSE",
                        "resp-student-v2",
                        "stok_" + "a".repeat(40),
                        "a".repeat(64),
                        "b".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResponsibilityStudentSourceReference(
                        "RESPONSIBILITY-STUDENT-REF",
                        "resp-student-v3",
                        "stok_" + "a".repeat(40),
                        "a".repeat(64),
                        "b".repeat(64)));
    }

    private static ResponsibilityRecipientEvidence validEvidence(
            AuthoritativeResponsibilityRelation relation) {
        return new ResponsibilityRecipientEvidence(
                relation, ACCOUNT_ID, COLLEGE_ID, true, true, true, true);
    }

    private static AuthoritativeResponsibilityRelation relation(
            EffectiveInterval interval) {
        return new AuthoritativeResponsibilityRelation(
                UUID.fromString("019c1234-0000-7000-8000-000000000203"),
                "SRC-P0-RESPONSIBILITY-001",
                "rtok_" + "c".repeat(40),
                new ResponsibilityStudentSourceReference(
                        "RESPONSIBILITY-STUDENT-REF",
                        "resp-student-v2",
                        "stok_" + "d".repeat(40),
                        "e".repeat(64),
                        "f".repeat(64)),
                "a".repeat(64),
                "b".repeat(64),
                ResponsibilityType.PRIMARY,
                ResponsibilityStatus.ACTIVE,
                interval,
                7,
                7,
                7,
                1,
                "c".repeat(64));
    }
}
