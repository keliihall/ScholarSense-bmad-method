package cn.edu.suda.scholarsense.identityaccess.domain;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Exact D4 action/object/policy/auth/checker binding; it contains no display identity. */
public record HighRiskApprovalBinding(
        UUID requestId,
        String requestDigest,
        String actionType,
        String makerPrincipalDigest,
        String authorizationContextDigest,
        String authenticationStateDigest,
        String objectType,
        String objectRefDigest,
        long objectVersion,
        String scopeDigest,
        String impactScopeDigest,
        DataSensitivity dataSensitivity,
        String currentState,
        String targetState,
        String reasonCode,
        String matrixVersion,
        String matrixDigest,
        String policyVersion,
        String policyDigest,
        String roleFieldPolicyVersion,
        String roleFieldPolicyDigest,
        String previewDigest,
        String checkerSetDigest,
        List<String> requiredCheckerPrincipalDigests,
        long authorizationGeneration,
        String traceId,
        String observationDecisionDigest,
        String memberSetDigest,
        String watermarksDigest,
        String qualityRecoveryPolicyVersion,
        String qualityRecoveryPolicyDigest) {

    /** Preserves the byte-compatible predecessor fused -> recovering binding shape. */
    public HighRiskApprovalBinding(
            UUID requestId, String requestDigest, String actionType,
            String makerPrincipalDigest, String authorizationContextDigest,
            String authenticationStateDigest, String objectType, String objectRefDigest,
            long objectVersion, String scopeDigest, String impactScopeDigest,
            DataSensitivity dataSensitivity, String currentState, String targetState,
            String reasonCode, String matrixVersion, String matrixDigest,
            String policyVersion, String policyDigest, String roleFieldPolicyVersion,
            String roleFieldPolicyDigest, String previewDigest, String checkerSetDigest,
            List<String> requiredCheckerPrincipalDigests, long authorizationGeneration,
            String traceId) {
        this(requestId, requestDigest, actionType, makerPrincipalDigest,
                authorizationContextDigest, authenticationStateDigest, objectType,
                objectRefDigest, objectVersion, scopeDigest, impactScopeDigest,
                dataSensitivity, currentState, targetState, reasonCode, matrixVersion,
                matrixDigest, policyVersion, policyDigest, roleFieldPolicyVersion,
                roleFieldPolicyDigest, previewDigest, checkerSetDigest,
                requiredCheckerPrincipalDigests, authorizationGeneration, traceId,
                null, null, null, null, null);
    }

    public HighRiskApprovalBinding {
        requireUuidV7(requestId);
        for (String digest : List.of(
                requestDigest, makerPrincipalDigest, authorizationContextDigest,
                authenticationStateDigest, objectRefDigest, scopeDigest, impactScopeDigest,
                matrixDigest, policyDigest, roleFieldPolicyDigest, previewDigest,
                checkerSetDigest)) requireDigest(digest);
        boolean predecessorPair = "fused".equals(currentState)
                && "recovering".equals(targetState);
        boolean finalizationPair = "recovering".equals(currentState)
                && "eligible".equals(targetState);
        if (!"quality-fuse.recover".equals(actionType)
                || !"RECOVERY_TASK".equals(objectType)
                || objectVersion < 1 || objectVersion > 9_007_199_254_740_991L
                || dataSensitivity != DataSensitivity.HIGHLY_SENSITIVE_DEIDENTIFIED
                || (!predecessorPair && !finalizationPair)
                || reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{2,63}")
                || !"HRAM-1.0.0".equals(matrixVersion)
                || !"HRAP-1.0.0".equals(policyVersion)
                || !version(roleFieldPolicyVersion)
                || authorizationGeneration < 0
                || traceId == null || !traceId.matches("(?!0{32})[0-9a-f]{32}")) {
            throw invalid();
        }
        if (predecessorPair) {
            if (observationDecisionDigest != null || memberSetDigest != null
                    || watermarksDigest != null || qualityRecoveryPolicyVersion != null
                    || qualityRecoveryPolicyDigest != null) throw invalid();
        } else {
            requireDigest(observationDecisionDigest);
            requireDigest(memberSetDigest);
            requireDigest(watermarksDigest);
            if (!"QRP-1.0.0".equals(qualityRecoveryPolicyVersion)) throw invalid();
            requireDigest(qualityRecoveryPolicyDigest);
        }
        requiredCheckerPrincipalDigests = List.copyOf(requiredCheckerPrincipalDigests).stream()
                .sorted(Comparator.naturalOrder()).toList();
        if (requiredCheckerPrincipalDigests.isEmpty()
                || requiredCheckerPrincipalDigests.size() > 128
                || requiredCheckerPrincipalDigests.stream().distinct().count()
                        != requiredCheckerPrincipalDigests.size()) {
            throw invalid();
        }
        requiredCheckerPrincipalDigests.forEach(HighRiskApprovalBinding::requireDigest);
        if (requiredCheckerPrincipalDigests.contains(makerPrincipalDigest)) throw invalid();
    }

    public enum DataSensitivity { INTERNAL, SENSITIVE, HIGHLY_SENSITIVE_DEIDENTIFIED }

    static void requireDigest(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) throw invalid();
    }

    static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) throw invalid();
    }

    static boolean version(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9-]*-[0-9]+\\.[0-9]+\\.[0-9]+");
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("HIGH_RISK_APPROVAL_VALUE_INVALID");
    }
}
