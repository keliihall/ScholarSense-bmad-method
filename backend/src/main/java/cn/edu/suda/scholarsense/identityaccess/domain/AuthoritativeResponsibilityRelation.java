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
        String payloadDigest,
        AccessInvalidationChangeKind changeKind,
        AccessInvalidationReason changeReason,
        java.time.Instant changeEffectiveAt,
        AccessInvalidationLineageId lineageId,
        UUID supersedesId) {
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
        boolean hasMetadata = changeKind != null
                || changeReason != null
                || changeEffectiveAt != null
                || lineageId != null
                || supersedesId != null;
        if (hasMetadata
                && (changeKind == null
                        || changeReason == null
                        || changeEffectiveAt == null
                        || lineageId == null)) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_V2_CHANGE_METADATA_INCOMPLETE");
        }
        if (supersedesId != null) {
            AuthorityValidation.uuidV7(
                    supersedesId, "RESPONSIBILITY_SUPERSEDES_ID");
        }
    }

    public AuthoritativeResponsibilityRelation(
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
        this(
                relationId,
                sourceId,
                relationRefToken,
                studentSourceReference,
                counselorAccountRefDigest,
                collegeOrganizationRefDigest,
                responsibilityType,
                status,
                effectiveInterval,
                sourceVersion,
                sourceWatermark,
                recordVersion,
                aggregateVersion,
                payloadDigest,
                null,
                null,
                null,
                null,
                null);
    }

    public boolean hasInvalidationMetadata() {
        return changeKind != null;
    }
}
