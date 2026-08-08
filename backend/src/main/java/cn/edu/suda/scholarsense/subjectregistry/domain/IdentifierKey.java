package cn.edu.suda.scholarsense.subjectregistry.domain;

import java.util.Objects;

public record IdentifierKey(
        String sourceId,
        IdentifierType identifierType,
        ProtectedIdentifierToken token) {

    public IdentifierKey {
        if (sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z-]+-[0-9]{3}$")) {
            throw new SubjectRegistryException(
                    SubjectRegistryErrorCode.SUBJECT_REGISTRY_IDENTIFIER_INVALID);
        }
        Objects.requireNonNull(identifierType);
        Objects.requireNonNull(token);
    }
}
