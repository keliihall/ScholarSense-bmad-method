package cn.edu.suda.scholarsense.auditoperations.application;

public final class AuditRetentionException extends RuntimeException {
    private final String code;

    public AuditRetentionException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
