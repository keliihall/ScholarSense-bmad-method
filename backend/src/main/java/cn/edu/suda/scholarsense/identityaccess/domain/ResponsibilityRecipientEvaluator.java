package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Fail-closed, heuristic-free current primary recipient evaluation. */
public final class ResponsibilityRecipientEvaluator {
    private ResponsibilityRecipientEvaluator() {}

    public static ResponsibilityRecipientDecision evaluate(
            List<ResponsibilityRecipientEvidence> evidence,
            Instant serverNow,
            boolean identityProjectionAvailable,
            boolean trustedClockAvailable) {
        Objects.requireNonNull(evidence, "evidence");
        if (!trustedClockAvailable || serverNow == null) {
            return ResponsibilityRecipientDecision.unavailable(
                    ResponsibilityRecipientReason.CLOCK_UNAVAILABLE);
        }
        if (!identityProjectionAvailable) {
            return ResponsibilityRecipientDecision.unavailable(
                    ResponsibilityRecipientReason.DEPENDENCY_WATERMARK_BEHIND);
        }
        var current = evidence.stream()
                .filter(value ->
                        value.relation().responsibilityType()
                                == ResponsibilityType.PRIMARY)
                .filter(value ->
                        value.relation().status() == ResponsibilityStatus.ACTIVE)
                .filter(value ->
                        value.relation().effectiveInterval().contains(serverNow))
                .toList();
        if (current.isEmpty()) {
            boolean atEnd = evidence.stream()
                    .map(ResponsibilityRecipientEvidence::relation)
                    .map(AuthoritativeResponsibilityRelation::effectiveInterval)
                    .map(EffectiveInterval::effectiveTo)
                    .anyMatch(serverNow::equals);
            return ResponsibilityRecipientDecision.invalid(
                    atEnd
                            ? ResponsibilityRecipientReason.EFFECTIVE_END_REACHED
                            : ResponsibilityRecipientReason.ZERO_RECIPIENT);
        }
        if (current.size() != 1) {
            return ResponsibilityRecipientDecision.invalid(
                    ResponsibilityRecipientReason.MULTIPLE_RECIPIENTS);
        }
        var candidate = current.getFirst();
        if (!candidate.accountPresent()) {
            return ResponsibilityRecipientDecision.invalid(
                    ResponsibilityRecipientReason.INACTIVE_RECIPIENT);
        }
        if (!candidate.accountActive()) {
            return ResponsibilityRecipientDecision.invalid(
                    ResponsibilityRecipientReason.INACTIVE_RECIPIENT);
        }
        if (!candidate.r1EmploymentActive()) {
            return ResponsibilityRecipientDecision.invalid(
                    ResponsibilityRecipientReason.NON_R1_RECIPIENT);
        }
        if (!candidate.collegePresent()) {
            return ResponsibilityRecipientDecision.invalid(
                    ResponsibilityRecipientReason.COLLEGE_MISSING);
        }
        if (!candidate.collegeActive()) {
            return ResponsibilityRecipientDecision.invalid(
                    ResponsibilityRecipientReason.COLLEGE_INACTIVE);
        }
        if (!candidate.collegeMatchesEmployment()) {
            return ResponsibilityRecipientDecision.invalid(
                    ResponsibilityRecipientReason.COLLEGE_MISMATCH);
        }
        var relation = candidate.relation();
        return new ResponsibilityRecipientDecision(
                ResponsibilityRecipientValidity.VALID,
                ResponsibilityRecipientReason.VALID,
                candidate.counselorAccountId(),
                candidate.collegeOrganizationId(),
                relation.sourceVersion(),
                relation.sourceWatermark(),
                relation.aggregateVersion());
    }
}
