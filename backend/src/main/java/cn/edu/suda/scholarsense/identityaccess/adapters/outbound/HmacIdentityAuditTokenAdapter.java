package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.AuditSearchToken;
import cn.edu.suda.scholarsense.identityaccess.application.AuditTokenDomain;
import cn.edu.suda.scholarsense.identityaccess.application.AuditTokenizationMetadata;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditTokenPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Objects;

/** Key material is supplied by the deployment KMS boundary and is never returned or logged. */
public final class HmacIdentityAuditTokenAdapter implements IdentityAuditTokenPort {
    private static final int MINIMUM_KEY_BYTES = 32;
    private static final int MAXIMUM_KEY_BYTES = 64;
    private final SecretKey key;
    private final String keyVersion;

    public HmacIdentityAuditTokenAdapter(SecretKey key, String keyVersion) {
        this.key = Objects.requireNonNull(key, "key");
        if (keyVersion == null || !keyVersion.matches("k[0-9]+")) {
            throw new IllegalArgumentException("AUDIT_TOKEN_KEY_VERSION_INVALID");
        }
        this.keyVersion = keyVersion;
    }

    /** Loads a deployment-mounted key through the same protected-file boundary as identity sync. */
    public static HmacIdentityAuditTokenAdapter fromMountedKey(
            Path keyPath, String keyVersion) {
        byte[] key = ProtectedIdentitySecurityMaterial.read(
                keyPath,
                MINIMUM_KEY_BYTES,
                MAXIMUM_KEY_BYTES,
                "IDENTITY_AUDIT_TOKEN_KEY_INVALID",
                "IDENTITY_AUDIT_TOKEN_KEY_UNAVAILABLE");
        try {
            return new HmacIdentityAuditTokenAdapter(
                    new SecretKeySpec(key, "HmacSHA256"), keyVersion);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public AuditTokenizationMetadata metadata() {
        return new AuditTokenizationMetadata("AUDIT-TOKENIZATION-1.0.0", keyVersion);
    }

    @Override
    public AuditSearchToken tokenize(AuditTokenDomain domain, String normalizedValue) {
        if (domain == null || normalizedValue == null || normalizedValue.isBlank()) {
            throw new IllegalArgumentException("AUDIT_TOKEN_INPUT_INVALID");
        }
        String canonical = Normalizer.normalize(normalizedValue.strip(), Normalizer.Form.NFKC);
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(key);
            byte[] digest = hmac.doFinal(
                    (domain.wireName() + "\0" + canonical).getBytes(StandardCharsets.UTF_8));
            return new AuditSearchToken(
                    domain.prefix() + "_v1_" + keyVersion + "_" + HexFormat.of().formatHex(digest),
                    "AUDIT-TOKENIZATION-1.0.0",
                    keyVersion);
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException("AUDIT_TOKENIZATION_UNAVAILABLE", unavailable);
        }
    }
}
