package cn.edu.suda.scholarsense.subjectregistry.application;

public record IdentifierProtectionContext(String sourceId, String identifierType, String purpose) {
    public IdentifierProtectionContext {
        if (sourceId == null || identifierType == null || purpose == null
                || sourceId.isBlank() || identifierType.isBlank() || purpose.isBlank()) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_PROTECTION_CONTEXT_INVALID");
        }
    }
}
