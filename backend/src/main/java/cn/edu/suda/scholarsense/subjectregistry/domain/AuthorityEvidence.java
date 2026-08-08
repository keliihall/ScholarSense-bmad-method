package cn.edu.suda.scholarsense.subjectregistry.domain;

import java.util.Objects;

public record AuthorityEvidence(
        String sourceId,
        IdentifierType identifierType,
        int activeApprovedCandidateCount,
        boolean approvedAuthorityRecord,
        boolean identifierPreviouslyIssued,
        boolean continuityProof) {

    public AuthorityEvidence {
        SubjectRegistryDomainRules.requireBounded(sourceId, 1, 64);
        Objects.requireNonNull(identifierType);
        if (activeApprovedCandidateCount < 0) {
            throw new IllegalArgumentException("candidate count cannot be negative");
        }
    }
}
