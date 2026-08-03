package cn.edu.suda.scholarsense.identityaccess.application;

public final class SensitiveFieldCryptoException extends RuntimeException {
    private final String code;

    public SensitiveFieldCryptoException(String code) {
        super(valid(code));
        this.code = code;
    }

    public String code() {
        return code;
    }

    private static String valid(String value) {
        if (value == null || !value.matches("FIELD_CRYPTO_[A-Z_]{3,96}")) {
            throw new IllegalArgumentException("FIELD_CRYPTO_ERROR_CODE_INVALID");
        }
        return value;
    }
}
