package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientDecision;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientEvidence;
import java.util.List;

/** Stable-equivalence student scope persisted with an incremental batch. */
public record ResponsibilityScopeProjectionUpdate(
        String studentSourceRefDigest,
        List<AuthoritativeResponsibilityRelation> resultingRelations,
        ResponsibilityRecipientDecision decision,
        List<ResponsibilityRecipientEvidence> recipientEvidence) {
    public ResponsibilityScopeProjectionUpdate {
        if (studentSourceRefDigest == null
                || !studentSourceRefDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SCOPE_STUDENT_REF_INVALID");
        }
        resultingRelations = List.copyOf(resultingRelations);
        if (resultingRelations.isEmpty()
                || resultingRelations.stream().anyMatch(relation ->
                        !studentSourceRefDigest.equals(
                                relation.studentSourceReference()
                                        .equivalenceDomain()))) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SCOPE_PROJECTION_INVALID");
        }
        java.util.Objects.requireNonNull(decision, "decision");
        recipientEvidence = List.copyOf(recipientEvidence);
        if (!recipientEvidence.isEmpty()
                && (recipientEvidence.size() != resultingRelations.size()
                        || recipientEvidence.stream().anyMatch(evidence ->
                                !studentSourceRefDigest.equals(
                                        evidence.relation()
                                                .studentSourceReference()
                                                .equivalenceDomain()))
                        || recipientEvidence.stream()
                                        .map(evidence -> evidence.relation()
                                                .relationRefToken())
                                        .distinct()
                                        .count()
                                != recipientEvidence.size())) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SCOPE_EVIDENCE_INVALID");
        }
    }

    public ResponsibilityScopeProjectionUpdate(
            String studentSourceRefDigest,
            List<AuthoritativeResponsibilityRelation> resultingRelations,
            ResponsibilityRecipientDecision decision) {
        this(studentSourceRefDigest, resultingRelations, decision, List.of());
    }
}
