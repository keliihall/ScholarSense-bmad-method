package cn.edu.suda.scholarsense.identityaccess.application;

public class IdentitySyncException extends RuntimeException {
    private final String code;

    public IdentitySyncException(String code) {
        super("identity synchronization could not be completed");
        this.code = code;
    }

    public IdentitySyncException(String code, Throwable cause) {
        super("identity synchronization could not be completed", cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
