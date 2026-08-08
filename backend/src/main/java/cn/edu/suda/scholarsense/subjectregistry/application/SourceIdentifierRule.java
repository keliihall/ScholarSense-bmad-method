package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierType;
import java.util.Objects;

public record SourceIdentifierRule(
        String sourceId,
        IdentifierType identifierType,
        String sourceOwner,
        NormalizationProfile normalizationProfile,
        boolean mayIssueStudentRef) {

    public SourceIdentifierRule {
        if (sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z-]+-[0-9]{3}$")) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_SOURCE_RULE_INVALID");
        }
        Objects.requireNonNull(identifierType);
        if (sourceOwner == null || sourceOwner.isBlank() || sourceOwner.length() > 128) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_SOURCE_OWNER_INVALID");
        }
        Objects.requireNonNull(normalizationProfile);
    }

    public static SourceIdentifierRule approved(
            String sourceId, IdentifierType identifierType, String sourceOwner,
            NormalizationProfile profile, boolean mayIssueStudentRef) {
        return new SourceIdentifierRule(
                sourceId, identifierType, sourceOwner, profile, mayIssueStudentRef);
    }
}
