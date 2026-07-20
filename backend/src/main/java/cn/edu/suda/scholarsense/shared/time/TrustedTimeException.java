package cn.edu.suda.scholarsense.shared.time;

public final class TrustedTimeException extends RuntimeException {
    private final String code;

    public TrustedTimeException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
