package cn.edu.suda.scholarsense.runtime;

public final class ConfigurationException extends IllegalArgumentException {

    private final String code;
    private final String field;

    ConfigurationException(String code, String field, String safeReason) {
        super(code + ": " + field + " " + safeReason);
        this.code = code;
        this.field = field;
    }

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }
}
