package cn.edu.suda.scholarsense.subjectregistry.domain;

import java.util.Objects;

public final class SubjectRegistryException extends RuntimeException {
    private final SubjectRegistryErrorCode code;

    public SubjectRegistryException(SubjectRegistryErrorCode code) {
        super(Objects.requireNonNull(code).name());
        this.code = code;
    }

    public SubjectRegistryErrorCode code() {
        return code;
    }
}
