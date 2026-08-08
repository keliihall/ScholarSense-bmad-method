package cn.edu.suda.scholarsense.subjectregistry.application;

public final class SubjectRegistryVersionConflictException extends RuntimeException {
    private final long currentVersion;

    public SubjectRegistryVersionConflictException(long currentVersion) {
        super("SUBJECT_REGISTRY_VERSION_CONFLICT");
        this.currentVersion = currentVersion;
    }

    public long currentVersion() { return currentVersion; }
}
