package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientValidity;
import java.util.Objects;
import java.util.UUID;

/** One exact affected responsibility scope and its post-commit state proof. */
public record IdentityCascadeScopeReadBack(
        AccessInvalidationLineageId accessLineageId,
        String studentEquivalenceDigest,
        UUID counselorAccountId,
        UUID collegeOrganizationId,
        ResponsibilityRecipientValidity expectedValidity,
        long identitySourceVersion,
        long identitySourceWatermark,
        long identityAggregateVersion,
        long scopeSourceVersion,
        long scopeSourceWatermark,
        long scopeAggregateVersion,
        ResponsibilityScopeReadBack readBack) {
    public IdentityCascadeScopeReadBack {
        Objects.requireNonNull(accessLineageId, "accessLineageId");
        if (studentEquivalenceDigest == null
                || !studentEquivalenceDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "IDENTITY_CASCADE_SCOPE_DIGEST_INVALID");
        }
        Objects.requireNonNull(counselorAccountId, "counselorAccountId");
        Objects.requireNonNull(
                collegeOrganizationId, "collegeOrganizationId");
        Objects.requireNonNull(expectedValidity, "expectedValidity");
        if (expectedValidity
                        == ResponsibilityRecipientValidity
                                .DEPENDENCY_UNAVAILABLE
                || identitySourceVersion < 1
                || identitySourceWatermark < 1
                || identityAggregateVersion < 1
                || scopeSourceVersion < 1
                || scopeSourceWatermark < 1
                || scopeAggregateVersion < 1) {
            throw new IllegalArgumentException(
                    "IDENTITY_CASCADE_SCOPE_VERSION_INVALID");
        }
        Objects.requireNonNull(readBack, "readBack");
    }
}
