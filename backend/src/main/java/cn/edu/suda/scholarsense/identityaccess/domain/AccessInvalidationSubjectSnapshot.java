package cn.edu.suda.scholarsense.identityaccess.domain;

public record AccessInvalidationSubjectSnapshot(
        String subjectToken,
        String scopeToken,
        String objectDigest,
        String tokenizationProfileVersion) {
    public AccessInvalidationSubjectSnapshot {
        AccessInvalidationValidation.token(
                subjectToken,
                "subtok_",
                "ACCESS_INVALIDATION_SUBJECT");
        AccessInvalidationValidation.token(
                scopeToken, "scptok_", "ACCESS_INVALIDATION_SCOPE");
        AccessInvalidationValidation.digest(
                objectDigest, "ACCESS_INVALIDATION_OBJECT");
        if (!"ACCESS-INVALIDATION-TOKENIZATION-1.0.0"
                .equals(tokenizationProfileVersion)) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_TOKENIZATION_PROFILE_INVALID");
        }
    }
}
