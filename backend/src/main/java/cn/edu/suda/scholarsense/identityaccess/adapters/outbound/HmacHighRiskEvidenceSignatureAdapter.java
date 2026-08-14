package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.HighRiskEvidenceSignaturePort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Deployment-key-backed signatures for receipts, tokens and execution leases. */
public final class HmacHighRiskEvidenceSignatureAdapter
        implements HighRiskEvidenceSignaturePort {
    private final SecretKeySpec key;
    private final String keyVersion;

    private HmacHighRiskEvidenceSignatureAdapter(byte[] bytes, String keyVersion) {
        if (bytes.length < 32 || bytes.length > 64
                || keyVersion == null || !keyVersion.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("IDENTITY_HIGH_RISK_SIGNING_KEY_INVALID");
        }
        this.key = new SecretKeySpec(bytes.clone(), "HmacSHA256");
        this.keyVersion = keyVersion;
    }

    public static HmacHighRiskEvidenceSignatureAdapter fromMountedKey(
            Path keyPath, String keyVersion) {
        byte[] bytes;
        try {
            if (keyPath == null || !keyPath.isAbsolute() || Files.isSymbolicLink(keyPath)) {
                throw new IllegalArgumentException("IDENTITY_HIGH_RISK_SIGNING_KEY_INVALID");
            }
            bytes = Files.readAllBytes(keyPath.normalize());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("IDENTITY_HIGH_RISK_SIGNING_KEY_UNAVAILABLE", failure);
        }
        try {
            return new HmacHighRiskEvidenceSignatureAdapter(bytes, keyVersion);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public SignedValue sign(String canonicalValue) {
        if (canonicalValue == null || canonicalValue.isBlank()) {
            throw new IllegalArgumentException("IDENTITY_HIGH_RISK_SIGNING_INPUT_INVALID");
        }
        try {
            byte[] input = canonicalValue.getBytes(StandardCharsets.UTF_8);
            String digest = "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input));
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(key);
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(hmac.doFinal(input));
            return new SignedValue(keyVersion, signature, digest);
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException("IDENTITY_HIGH_RISK_SIGNING_UNAVAILABLE", unavailable);
        }
    }

    @Override
    public boolean verify(
            String canonicalValue,
            String requestedKeyVersion,
            String requestedSignature,
            String requestedDigest) {
        if (!keyVersion.equals(requestedKeyVersion) || canonicalValue == null
                || requestedSignature == null || requestedDigest == null) {
            return false;
        }
        SignedValue expected = sign(canonicalValue);
        return MessageDigest.isEqual(
                        expected.signature().getBytes(StandardCharsets.US_ASCII),
                        requestedSignature.getBytes(StandardCharsets.US_ASCII))
                && MessageDigest.isEqual(
                        expected.digest().getBytes(StandardCharsets.US_ASCII),
                        requestedDigest.getBytes(StandardCharsets.US_ASCII));
    }
}
