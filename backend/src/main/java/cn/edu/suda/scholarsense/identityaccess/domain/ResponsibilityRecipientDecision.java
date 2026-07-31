package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.Objects;
import java.util.UUID;

public record ResponsibilityRecipientDecision(
        ResponsibilityRecipientValidity validity,
        ResponsibilityRecipientReason reason,
        UUID counselorAccountId,
        UUID collegeOrganizationId,
        long sourceVersion,
        long sourceWatermark,
        long aggregateVersion) {
    public ResponsibilityRecipientDecision {
        Objects.requireNonNull(validity, "validity");
        Objects.requireNonNull(reason, "reason");
        if (validity == ResponsibilityRecipientValidity.VALID) {
            AuthorityValidation.uuidV7(
                    counselorAccountId, "RESPONSIBILITY_COUNSELOR_ACCOUNT_ID");
            AuthorityValidation.uuidV7(
                    collegeOrganizationId,
                    "RESPONSIBILITY_COLLEGE_ORGANIZATION_ID");
            AuthorityValidation.positive(sourceVersion, "RESPONSIBILITY_SOURCE");
            AuthorityValidation.positive(
                    sourceWatermark, "RESPONSIBILITY_SOURCE_WATERMARK");
            AuthorityValidation.positive(
                    aggregateVersion, "RESPONSIBILITY_AGGREGATE");
            if (reason != ResponsibilityRecipientReason.VALID) {
                throw new IllegalArgumentException(
                        "RESPONSIBILITY_VALID_REASON_MISMATCH");
            }
        } else if (counselorAccountId != null || collegeOrganizationId != null) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_INVALID_RECIPIENT_LEAK");
        }
    }

    public static ResponsibilityRecipientDecision invalid(
            ResponsibilityRecipientReason reason) {
        return new ResponsibilityRecipientDecision(
                ResponsibilityRecipientValidity.INVALID,
                reason,
                null,
                null,
                0,
                0,
                0);
    }

    public static ResponsibilityRecipientDecision unavailable(
            ResponsibilityRecipientReason reason) {
        return new ResponsibilityRecipientDecision(
                ResponsibilityRecipientValidity.DEPENDENCY_UNAVAILABLE,
                reason,
                null,
                null,
                0,
                0,
                0);
    }
}
