package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMappingException;
import java.util.Objects;

public record SubjectMappingExceptionRecord(
        SubjectMappingException exception,
        ProtectedIdentifierMaterial officialIdentifier) {
    public SubjectMappingExceptionRecord {
        Objects.requireNonNull(exception);
        Objects.requireNonNull(officialIdentifier);
    }

    @Override
    public String toString() {
        return "SubjectMappingExceptionRecord[exceptionId=" + exception.exceptionId()
                + ", protected=REDACTED]";
    }
}
