package cn.edu.suda.scholarsense.signalevaluation.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Validated internal computation request; public API values are copied at the facade. */
public record RecoverySampleComputationRequest(
        String providerVersion,
        String recoveryRequestId,
        String episodeId,
        String taskId,
        String ruleVersionsDigest,
        String memberSetDigest,
        String watermarksDigest,
        String qualityRecoveryPolicyVersion,
        String qualityRecoveryPolicyDigest,
        String opaqueSubjectWindowSelectionRef,
        String selectionSeed,
        int requestedSampleCount,
        String requestDigest,
        String traceId) {

    public static final String PROVIDER_VERSION = "RECOVERY-SAMPLE-PROVIDER-1.0.0";
    public static final String POLICY_VERSION = "QRP-1.0.0";
    public static final String POLICY_DIGEST =
            "sha256:195a22553b13ac35da5923e704ef8385f85d17574b8a753cc1afc16c2055f366";
    public static final int MAXIMUM_SELECTED_WINDOWS = 10_000;
    public static final int MAXIMUM_STRATA = 128;
    public static final long TIMEOUT_NANOS = 30_000_000_000L;

    public RecoverySampleComputationRequest {
        String uuid = "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
        String digest = "sha256:[0-9a-f]{64}";
        if (!PROVIDER_VERSION.equals(providerVersion)
                || recoveryRequestId == null || !recoveryRequestId.matches(uuid)
                || episodeId == null || !episodeId.matches(uuid)
                || taskId == null || !taskId.matches(uuid)
                || ruleVersionsDigest == null || !ruleVersionsDigest.matches(digest)
                || memberSetDigest == null || !memberSetDigest.matches(digest)
                || watermarksDigest == null || !watermarksDigest.matches(digest)
                || !POLICY_VERSION.equals(qualityRecoveryPolicyVersion)
                || !POLICY_DIGEST.equals(qualityRecoveryPolicyDigest)
                || opaqueSubjectWindowSelectionRef == null
                || !opaqueSubjectWindowSelectionRef.matches("oswref:v1:[0-9a-f]{64}")
                || selectionSeed == null || !selectionSeed.matches(digest)
                || requestedSampleCount < 1 || requestedSampleCount > MAXIMUM_SELECTED_WINDOWS
                || requestDigest == null || !requestDigest.matches(digest)
                || traceId == null || !traceId.matches("(?!0{32})[0-9a-f]{32}")) {
            throw new IllegalArgumentException("RECOVERY_SAMPLE_COMPUTATION_REQUEST_INVALID");
        }
    }

    public String canonicalBody() {
        return String.join("\n", providerVersion, recoveryRequestId, episodeId, taskId,
                ruleVersionsDigest, memberSetDigest, watermarksDigest,
                qualityRecoveryPolicyVersion, qualityRecoveryPolicyDigest,
                opaqueSubjectWindowSelectionRef, selectionSeed,
                Integer.toString(requestedSampleCount), traceId);
    }

    public String canonicalRequestDigest() {
        return digest(canonicalBody());
    }

    static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
        }
    }
}
