package cn.edu.suda.scholarsense.subjectregistry.application;

public final class IdentifierProtectionUnavailableException extends RuntimeException {
    public IdentifierProtectionUnavailableException() {
        super("SUBJECT_REGISTRY_PROTECTION_KEY_UNAVAILABLE");
    }
}
