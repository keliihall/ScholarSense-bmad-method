package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseWorkItemIdentity;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseWorkItemKeyPort;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/** Derives opaque episode identities without exposing deployment-managed key material. */
public final class HmacQualityFuseWorkItemKeyProvider
        implements QualityFuseWorkItemKeyPort {
    private static final String DOMAIN =
            "scholarsense\0quality-fuse\0work-item-key\0v1\0";

    private final SecretKey key;
    private final String keyVersion;

    public HmacQualityFuseWorkItemKeyProvider(SecretKey key, String keyVersion) {
        this.key = Objects.requireNonNull(key);
        if (!"HmacSHA256".equalsIgnoreCase(key.getAlgorithm())
                || keyVersion == null || !keyVersion.matches("^k[1-9][0-9]*$")) {
            throw new IllegalArgumentException(
                    "INGESTION_QUALITY_FUSE_WORK_ITEM_KEY_INVALID");
        }
        this.keyVersion = keyVersion;
    }

    @Override
    public QualityFuseWorkItemIdentity current(
            String sourceId, String dependencyId, long episodeGeneration) {
        if (sourceId == null || !sourceId.matches("^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$")
                || dependencyId == null
                || !dependencyId.matches("^DEP-P[01]-[A-Z0-9-]+-[0-9]{3}$")
                || episodeGeneration < 1 || episodeGeneration > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException(
                    "INGESTION_QUALITY_FUSE_WORK_ITEM_INPUT_INVALID");
        }
        String canonical = DOMAIN + sourceId + "\0" + dependencyId + "\0"
                + episodeGeneration;
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(key);
            byte[] digest = hmac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return new QualityFuseWorkItemIdentity(
                    "qf:" + HexFormat.of().formatHex(digest), keyVersion);
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException(
                    "INGESTION_QUALITY_FUSE_WORK_ITEM_HMAC_UNAVAILABLE", unavailable);
        }
    }
}
