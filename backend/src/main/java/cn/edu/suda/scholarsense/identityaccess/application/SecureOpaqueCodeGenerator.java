package cn.edu.suda.scholarsense.identityaccess.application;

import java.security.SecureRandom;
import java.util.Base64;

public final class SecureOpaqueCodeGenerator implements OpaqueCodeGenerator {
    private final SecureRandom random = new SecureRandom();
    private final String prefix;

    public SecureOpaqueCodeGenerator(String prefix) {
        if (prefix == null || !prefix.matches("[a-z]{2}_")) {
            throw new IllegalArgumentException("OPAQUE_CODE_PREFIX_INVALID");
        }
        this.prefix = prefix;
    }

    @Override
    public String generate() {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }
}
