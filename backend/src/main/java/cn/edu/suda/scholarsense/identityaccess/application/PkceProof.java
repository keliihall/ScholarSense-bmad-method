package cn.edu.suda.scholarsense.identityaccess.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public record PkceProof(String verifier, String challenge, String challengeMethod) {
    public static PkceProof generate() {
        byte[] entropy = new byte[64];
        new SecureRandom().nextBytes(entropy);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        return new PkceProof(verifier, challengeFor(verifier), "S256");
    }

    public static String challengeFor(String verifier) {
        if (verifier == null || !verifier.matches("[A-Za-z0-9._~-]{43,128}")) {
            throw new IllegalArgumentException("PKCE_VERIFIER_INVALID");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
