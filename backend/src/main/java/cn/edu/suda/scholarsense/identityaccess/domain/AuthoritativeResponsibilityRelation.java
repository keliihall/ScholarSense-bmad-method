package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.Objects;
import java.util.UUID;

/** Immutable provider-neutral responsibility fact; it never owns canonical StudentRef. */
public record AuthoritativeResponsibilityRelation(
        UUID relationId,
        String sourceId,
        String relationRefToken,
        ResponsibilityStudentSourceReference studentSourceReference,
        String counselorAccountRefDigest,
        String collegeOrganizationRefDigest,
        ResponsibilityType responsibilityType,
        ResponsibilityStatus status,
        EffectiveInterval effectiveInterval,
        long sourceVersion,
        long sourceWatermark,
        long recordVersion,
        long aggregateVersion,
        String payloadDigest) {
    public AuthoritativeResponsibilityRelation {
        AuthorityValidation.uuidV7(relationId, "RESPONSIBILITY_RELATION_ID");
        AuthorityValidation.sourceId(sourceId);
        if (relationRefToken == null
                || !relationRefToken.matches("rtok_[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_RELATION_REF_INVALID");
        }
        Objects.requireNonNull(studentSourceReference, "studentSourceReference");
        AuthorityValidation.digest(
                counselorAccountRefDigest, "RESPONSIBILITY_COUNSELOR_REF");
        AuthorityValidation.digest(
                collegeOrganizationRefDigest, "RESPONSIBILITY_COLLEGE_REF");
        Objects.requireNonNull(responsibilityType, "responsibilityType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(effectiveInterval, "effectiveInterval");
        AuthorityValidation.positive(sourceVersion, "RESPONSIBILITY_SOURCE");
        AuthorityValidation.positive(
                sourceWatermark, "RESPONSIBILITY_SOURCE_WATERMARK");
        AuthorityValidation.positive(recordVersion, "RESPONSIBILITY_RECORD");
        AuthorityValidation.positive(aggregateVersion, "RESPONSIBILITY_AGGREGATE");
        AuthorityValidation.digest(payloadDigest, "RESPONSIBILITY_PAYLOAD");
    }
}
