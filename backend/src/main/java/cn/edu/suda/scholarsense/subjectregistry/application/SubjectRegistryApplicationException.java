package cn.edu.suda.scholarsense.subjectregistry.application;

public final class SubjectRegistryApplicationException extends RuntimeException {
    private final String code;
    private final int httpStatus;
    private final Long currentVersion;

    public SubjectRegistryApplicationException(String code) {
        this(code, statusFor(code), null);
    }

    public SubjectRegistryApplicationException(String code, Long currentVersion) {
        this(code, statusFor(code), currentVersion);
    }

    public SubjectRegistryApplicationException(String code, int httpStatus, Long currentVersion) {
        super(code);
        this.code = code;
        this.httpStatus = httpStatus;
        this.currentVersion = currentVersion;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }
    public Long currentVersion() { return currentVersion; }

    private static int statusFor(String code) {
        return switch (code) {
            case "SUBJECT_REGISTRY_FORBIDDEN", "SUBJECT_REGISTRY_NOT_FOUND" -> 404;
            case "SUBJECT_REGISTRY_VERSION_CONFLICT",
                    "SUBJECT_REGISTRY_IDEMPOTENCY_MISMATCH",
                    "SUBJECT_REGISTRY_MAPPING_OVERLAP",
                    "SUBJECT_REGISTRY_INVALID_TRANSITION" -> 409;
            case "SUBJECT_REGISTRY_DEPENDENCY_UNAVAILABLE",
                    "SUBJECT_REGISTRY_TIME_SOURCE_UNAVAILABLE",
                    "SUBJECT_REGISTRY_PROTECTION_KEY_UNAVAILABLE",
                    "SUBJECT_REGISTRY_AUDIT_UNAVAILABLE" -> 503;
            default -> 400;
        };
    }
}
