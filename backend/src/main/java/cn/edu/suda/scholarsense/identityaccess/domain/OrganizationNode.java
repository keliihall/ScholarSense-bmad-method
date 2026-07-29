package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.Objects;
import java.util.UUID;

public record OrganizationNode(
        UUID organizationId,
        String sourceId,
        String externalRefDigest,
        String parentExternalRefDigest,
        String displayName,
        OrganizationType organizationType,
        AuthoritativeStatus status,
        EffectiveInterval effectiveInterval,
        long sourceVersion,
        long aggregateVersion) {
    public OrganizationNode {
        AuthorityValidation.uuidV7(organizationId, "ORGANIZATION_ID");
        AuthorityValidation.sourceId(sourceId);
        AuthorityValidation.digest(externalRefDigest, "ORGANIZATION_EXTERNAL_REF");
        if (parentExternalRefDigest != null) {
            AuthorityValidation.digest(parentExternalRefDigest, "ORGANIZATION_PARENT");
        }
        if (displayName == null
                || displayName.isBlank()
                || displayName.length() > 128
                || !displayName.equals(displayName.strip())
                || displayName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("ORGANIZATION_DISPLAY_NAME_INVALID");
        }
        Objects.requireNonNull(organizationType, "organizationType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(effectiveInterval, "effectiveInterval");
        AuthorityValidation.positive(sourceVersion, "SOURCE");
        AuthorityValidation.positive(aggregateVersion, "AGGREGATE");
    }
}
