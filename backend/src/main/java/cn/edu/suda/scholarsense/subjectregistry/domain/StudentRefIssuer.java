package cn.edu.suda.scholarsense.subjectregistry.domain;

import java.util.Objects;
import java.util.Set;

public final class StudentRefIssuer {
    public static final String AUTHORITY_SOURCE_ID = "SRC-P0-STUDENT-001";

    private StudentRefIssuer() {}

    public static StudentRefIssuanceDecision decide(AuthorityEvidence evidence) {
        Objects.requireNonNull(evidence);
        if (evidence.activeApprovedCandidateCount() == 0) {
            return StudentRefIssuanceDecision.ISOLATE_NO_MATCH;
        }
        if (evidence.activeApprovedCandidateCount() > 1) {
            return StudentRefIssuanceDecision.ISOLATE_AMBIGUOUS;
        }
        if (evidence.identifierPreviouslyIssued() && !evidence.continuityProof()) {
            return StudentRefIssuanceDecision.ISOLATE_REISSUE_UNPROVEN;
        }
        boolean mayIssue = AUTHORITY_SOURCE_ID.equals(evidence.sourceId())
                && evidence.identifierType() == IdentifierType.STUDENT_NUMBER
                && evidence.approvedAuthorityRecord()
                && !evidence.identifierPreviouslyIssued();
        return mayIssue
                ? StudentRefIssuanceDecision.ISSUE_STUDENT_REF
                : StudentRefIssuanceDecision.RESOLVE_EXISTING_ONLY;
    }

    public static StudentRef issue(
            AuthorityEvidence evidence, StudentRef candidate, Set<StudentRef> reservations) {
        Objects.requireNonNull(candidate);
        Set<StudentRef> immutableReservations = Set.copyOf(Objects.requireNonNull(reservations));
        if (decide(evidence) != StudentRefIssuanceDecision.ISSUE_STUDENT_REF) {
            throw new SubjectRegistryException(
                    SubjectRegistryErrorCode.SUBJECT_REGISTRY_AUTHORITY_PROOF_REQUIRED);
        }
        if (immutableReservations.contains(candidate)) {
            throw new SubjectRegistryException(
                    SubjectRegistryErrorCode.SUBJECT_REGISTRY_STUDENT_REF_REUSE);
        }
        return candidate;
    }
}
