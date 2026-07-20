package cn.edu.suda.scholarsense.identityaccess.domain;

/** Stable fail-closed identity-access failure; messages never include sensitive inputs. */
public final class IdentityAccessException extends RuntimeException {
    private final String code;

    public IdentityAccessException(String code, String message) {
        super(message);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException("IDENTITY_ERROR_CODE_INVALID");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
