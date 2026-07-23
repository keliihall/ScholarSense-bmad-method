package cn.edu.suda.scholarsense.auditoperations.application;

public final class AuditArchiveException extends RuntimeException {
    private final String code;

    public AuditArchiveException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
