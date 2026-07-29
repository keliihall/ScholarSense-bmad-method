package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.Objects;
import java.util.UUID;

public record EmploymentRoleBinding(
        UUID bindingId,
        UUID accountId,
        UUID organizationId,
        String externalRefDigest,
        String sourceRoleCode,
        TargetRole targetRole,
        String mappingVersion,
        AuthoritativeStatus status,
        EffectiveInterval effectiveInterval,
        long sourceVersion,
        long aggregateVersion) {
    public EmploymentRoleBinding {
        AuthorityValidation.uuidV7(bindingId, "ROLE_BINDING_ID");
        AuthorityValidation.uuidV7(accountId, "ACCOUNT_ID");
        AuthorityValidation.uuidV7(organizationId, "ORGANIZATION_ID");
        AuthorityValidation.digest(externalRefDigest, "ROLE_BINDING_EXTERNAL_REF");
        if (sourceRoleCode == null || !sourceRoleCode.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("IDENTITY_ROLE_SOURCE_CODE_INVALID");
        }
        Objects.requireNonNull(targetRole, "targetRole");
        if (!"IDENTITY-ROLE-MAPPING-1.0.0".equals(mappingVersion)) {
            throw new IllegalArgumentException("IDENTITY_ROLE_MAPPING_UNAPPROVED");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(effectiveInterval, "effectiveInterval");
        AuthorityValidation.positive(sourceVersion, "SOURCE");
        AuthorityValidation.positive(aggregateVersion, "AGGREGATE");
    }
}
