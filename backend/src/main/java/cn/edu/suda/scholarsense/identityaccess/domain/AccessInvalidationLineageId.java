package cn.edu.suda.scholarsense.identityaccess.domain;

public record AccessInvalidationLineageId(String value) {
    public AccessInvalidationLineageId {
        AccessInvalidationValidation.token(
                value, "lin_", "ACCESS_INVALIDATION_LINEAGE");
    }
}
