package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.Objects;
import java.util.UUID;

/** Same-baseline identity/organization evidence used to validate one relation. */
public record ResponsibilityRecipientEvidence(
        AuthoritativeResponsibilityRelation relation,
        UUID counselorAccountId,
        UUID collegeOrganizationId,
        boolean accountPresent,
        boolean accountActive,
        boolean r1EmploymentActive,
        boolean collegePresent,
        boolean collegeActive,
        boolean collegeMatchesEmployment) {
    public ResponsibilityRecipientEvidence {
        Objects.requireNonNull(relation, "relation");
        if (counselorAccountId != null) {
            AuthorityValidation.uuidV7(
                    counselorAccountId,
                    "RESPONSIBILITY_COUNSELOR_ACCOUNT_ID");
        }
        if (collegeOrganizationId != null) {
            AuthorityValidation.uuidV7(
                    collegeOrganizationId,
                    "RESPONSIBILITY_COLLEGE_ORGANIZATION_ID");
        }
        if (accountPresent != (counselorAccountId != null)
                || collegePresent != (collegeOrganizationId != null)
                || accountActive && !accountPresent
                || r1EmploymentActive
                        && (!accountPresent || !collegePresent)
                || collegeActive && !collegePresent
                || collegeMatchesEmployment
                        && (!accountPresent || !collegePresent)) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_RECIPIENT_EVIDENCE_INVALID");
        }
    }

    public ResponsibilityRecipientEvidence(
            AuthoritativeResponsibilityRelation relation,
            UUID counselorAccountId,
            UUID collegeOrganizationId,
            boolean accountActive,
            boolean r1EmploymentActive,
            boolean collegeActive,
            boolean collegeMatchesEmployment) {
        this(
                relation,
                counselorAccountId,
                collegeOrganizationId,
                counselorAccountId != null,
                accountActive,
                r1EmploymentActive,
                collegeOrganizationId != null,
                collegeActive,
                collegeMatchesEmployment);
    }
}
