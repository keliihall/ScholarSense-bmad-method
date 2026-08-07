package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.subjectregistry.domain.ProtectedIdentifierToken;
import java.util.Objects;

public record ProtectedIdentifierMaterial(
        ProtectedIdentifierToken token,
        String ciphertext,
        String purpose) {
    public ProtectedIdentifierMaterial {
        Objects.requireNonNull(token);
        if (ciphertext == null || ciphertext.isBlank() || ciphertext.length() > 4096
                || purpose == null || purpose.isBlank() || purpose.length() > 64) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_PROTECTED_IDENTIFIER_INVALID");
        }
    }

    @Override
    public String toString() {
        return "ProtectedIdentifierMaterial[REDACTED]";
    }
}
