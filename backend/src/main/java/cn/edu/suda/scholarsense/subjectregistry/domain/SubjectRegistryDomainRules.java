package cn.edu.suda.scholarsense.subjectregistry.domain;

import java.util.UUID;

final class SubjectRegistryDomainRules {
    static final long MAX_SAFE_VERSION = 9_007_199_254_740_991L;

    private SubjectRegistryDomainRules() {}

    static UUID requireUuidV7(UUID value, SubjectRegistryErrorCode code) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new SubjectRegistryException(code);
        }
        return value;
    }

    static String requireBounded(String value, int minLength, int maxLength) {
        if (value == null || value.length() < minLength || value.length() > maxLength) {
            throw new SubjectRegistryException(SubjectRegistryErrorCode.SUBJECT_REGISTRY_IDENTIFIER_INVALID);
        }
        return value;
    }
}
