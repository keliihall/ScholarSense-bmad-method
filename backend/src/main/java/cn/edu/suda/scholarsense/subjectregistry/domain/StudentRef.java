package cn.edu.suda.scholarsense.subjectregistry.domain;

import java.util.UUID;

public record StudentRef(UUID value) {
    public StudentRef {
        SubjectRegistryDomainRules.requireUuidV7(
                value, SubjectRegistryErrorCode.SUBJECT_REGISTRY_STUDENT_REF_INVALID);
    }

    public static StudentRef of(UUID value) {
        return new StudentRef(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
