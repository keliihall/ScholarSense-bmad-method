package cn.edu.suda.scholarsense.identityaccess.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Domain-separated MAC boundary for approved V2 cutover commands. */
@FunctionalInterface
public interface ResponsibilityV2CutoverCommandSignaturePort {
    String sign(byte[] canonicalPayload);

    default boolean verify(
            byte[] canonicalPayload, String signatureDigest) {
        if (signatureDigest == null
                || !signatureDigest.matches("[0-9a-f]{64}")) {
            return false;
        }
        String expected = sign(canonicalPayload);
        return expected != null
                && expected.matches("[0-9a-f]{64}")
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        signatureDigest.getBytes(
                                StandardCharsets.US_ASCII));
    }

    static ResponsibilityV2CutoverCommandSignaturePort rejectAll() {
        return ignored -> null;
    }
}
