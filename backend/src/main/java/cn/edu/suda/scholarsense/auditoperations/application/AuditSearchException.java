package cn.edu.suda.scholarsense.auditoperations.application;

public final class AuditSearchException extends RuntimeException {
    private final String code;

    public AuditSearchException(String code) {
        super(code);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("AUDIT_SEARCH_ERROR_CODE_INVALID");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
