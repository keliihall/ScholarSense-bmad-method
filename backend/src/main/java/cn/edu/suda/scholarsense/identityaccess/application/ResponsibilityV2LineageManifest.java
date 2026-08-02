package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Source-signed summary of one complete V2 responsibility event lineage. */
public record ResponsibilityV2LineageManifest(
        String relationRefToken,
        AccessInvalidationLineageId lineageId,
        UUID rootEventId,
        UUID headEventId,
        long eventCount,
        String canonicalChainDigest) {
    public ResponsibilityV2LineageManifest {
        if (relationRefToken == null
                || !relationRefToken.matches(
                        "rtok_[A-Za-z0-9_-]{32,128}")
                || lineageId == null
                || rootEventId == null
                || rootEventId.version() != 7
                || rootEventId.variant() != 2
                || headEventId == null
                || headEventId.version() != 7
                || headEventId.variant() != 2
                || eventCount < 1
                || canonicalChainDigest == null
                || !canonicalChainDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_V2_LINEAGE_MANIFEST_INVALID");
        }
    }

    public static String digest(
            List<ResponsibilityV2LineageManifest> manifests) {
        String canonical = manifests.stream()
                .sorted(Comparator.comparing(
                        manifest -> manifest.lineageId().value()))
                .map(manifest -> manifest.relationRefToken()
                        + "|" + manifest.lineageId().value()
                        + "|" + manifest.rootEventId()
                        + "|" + manifest.headEventId()
                        + "|" + manifest.eventCount()
                        + "|" + manifest.canonicalChainDigest())
                .collect(Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(
                                    StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
