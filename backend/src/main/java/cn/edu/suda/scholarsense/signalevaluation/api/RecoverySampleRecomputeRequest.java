package cn.edu.suda.scholarsense.signalevaluation.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Exact, transport-neutral and PII-free recovery sample query. */
public record RecoverySampleRecomputeRequest(
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

    public static final String QUALITY_RECOVERY_POLICY_VERSION = "QRP-1.0.0";
    public static final String QUALITY_RECOVERY_POLICY_DIGEST =
            "sha256:195a22553b13ac35da5923e704ef8385f85d17574b8a753cc1afc16c2055f366";
    private static final String UUID_V7 =
            "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private static final String DIGEST = "sha256:[0-9a-f]{64}";

    public RecoverySampleRecomputeRequest {
        if (!RecoverySampleRecomputeProviderPort.PROVIDER_VERSION.equals(providerVersion)
                || !matches(recoveryRequestId, UUID_V7)
                || !matches(episodeId, UUID_V7)
                || !matches(taskId, UUID_V7)
                || !matches(ruleVersionsDigest, DIGEST)
                || !matches(memberSetDigest, DIGEST)
                || !matches(watermarksDigest, DIGEST)
                || !QUALITY_RECOVERY_POLICY_VERSION.equals(qualityRecoveryPolicyVersion)
                || !QUALITY_RECOVERY_POLICY_DIGEST.equals(qualityRecoveryPolicyDigest)
                || opaqueSubjectWindowSelectionRef == null
                || !opaqueSubjectWindowSelectionRef.matches("oswref:v1:[0-9a-f]{64}")
                || !matches(selectionSeed, DIGEST)
                || requestedSampleCount < 1
                || requestedSampleCount
                        > RecoverySampleRecomputeProviderPort.MAXIMUM_SELECTED_SUBJECT_WINDOWS
                || !matches(requestDigest, DIGEST)
                || traceId == null
                || !traceId.matches("(?!0{32})[0-9a-f]{32}")) {
            throw new IllegalArgumentException("RECOVERY_SAMPLE_REQUEST_INVALID");
        }
        int wireBytes = canonicalBodyOf(
                providerVersion, recoveryRequestId, episodeId, taskId,
                ruleVersionsDigest, memberSetDigest, watermarksDigest,
                qualityRecoveryPolicyVersion, qualityRecoveryPolicyDigest,
                opaqueSubjectWindowSelectionRef, selectionSeed, requestedSampleCount, traceId)
                .getBytes(StandardCharsets.UTF_8).length
                + requestDigest.getBytes(StandardCharsets.UTF_8).length;
        if (wireBytes > RecoverySampleRecomputeProviderPort.MAXIMUM_WIRE_BYTES) {
            throw new IllegalArgumentException("RECOVERY_SAMPLE_REQUEST_INVALID");
        }
    }

    /** Stable body binding excludes the idempotency digest itself. */
    public String canonicalBody() {
        return canonicalBodyOf(providerVersion, recoveryRequestId, episodeId, taskId,
                ruleVersionsDigest, memberSetDigest, watermarksDigest,
                qualityRecoveryPolicyVersion, qualityRecoveryPolicyDigest,
                opaqueSubjectWindowSelectionRef, selectionSeed,
                requestedSampleCount, traceId);
    }

    public String canonicalRequestDigest() {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalBody().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
        }
    }

    private static String canonicalBodyOf(
            String providerVersion, String recoveryRequestId, String episodeId, String taskId,
            String ruleVersionsDigest, String memberSetDigest, String watermarksDigest,
            String qualityRecoveryPolicyVersion, String qualityRecoveryPolicyDigest,
            String opaqueSubjectWindowSelectionRef, String selectionSeed,
            int requestedSampleCount, String traceId) {
        return String.join("\n", providerVersion, recoveryRequestId, episodeId, taskId,
                ruleVersionsDigest, memberSetDigest, watermarksDigest,
                qualityRecoveryPolicyVersion, qualityRecoveryPolicyDigest,
                opaqueSubjectWindowSelectionRef, selectionSeed,
                Integer.toString(requestedSampleCount), traceId);
    }

    private static boolean matches(String value, String pattern) {
        return value != null && value.matches(pattern);
    }
}
