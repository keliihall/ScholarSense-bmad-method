package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Signed single-use D4 token value; issuance persistence owns the consumed flag. */
public record HighRiskExecutionToken(
        UUID tokenJti,
        UUID approvalId,
        long approvalVersion,
        String approvalReceiptDigest,
        HighRiskApprovalBinding binding,
        Instant issuedAt,
        Instant authorizedUntil,
        String audience,
        String keyVersion,
        String signature) {

    public static final Duration AUTHORIZATION_DURATION = Duration.ofMinutes(15);

    public HighRiskExecutionToken {
        HighRiskApprovalBinding.requireUuidV7(tokenJti);
        HighRiskApprovalBinding.requireUuidV7(approvalId);
        if (approvalVersion < 1 || binding == null) throw HighRiskApprovalBinding.invalid();
        HighRiskApprovalBinding.requireDigest(approvalReceiptDigest);
        if (issuedAt == null || authorizedUntil == null
                || issuedAt.getNano() % 1_000 != 0 || authorizedUntil.getNano() % 1_000 != 0
                || !authorizedUntil.equals(issuedAt.plus(AUTHORIZATION_DURATION))
                || audience == null || !audience.matches("[a-z][a-z0-9-]{2,63}")
                || keyVersion == null || !keyVersion.matches("[A-Za-z0-9._-]{1,64}")
                || signature == null || !signature.matches("[A-Za-z0-9_-]{43,512}")) {
            throw HighRiskApprovalBinding.invalid();
        }
    }

    public String canonicalUnsigned() {
        String predecessor = String.join("\n",
                "HIGH-RISK-EXECUTION-TOKEN-1.0.0",
                tokenJti.toString(), approvalId.toString(), Long.toString(approvalVersion),
                approvalReceiptDigest, binding.requestId().toString(), binding.requestDigest(),
                binding.actionType(), binding.makerPrincipalDigest(),
                binding.authorizationContextDigest(), binding.authenticationStateDigest(),
                binding.objectType(), binding.objectRefDigest(),
                Long.toString(binding.objectVersion()), binding.scopeDigest(),
                binding.impactScopeDigest(), binding.dataSensitivity().name(),
                binding.currentState(), binding.targetState(), binding.reasonCode(),
                binding.matrixVersion(), binding.matrixDigest(), binding.policyVersion(),
                binding.policyDigest(), binding.roleFieldPolicyVersion(),
                binding.roleFieldPolicyDigest(), binding.previewDigest(),
                binding.checkerSetDigest(),
                String.join(",", binding.requiredCheckerPrincipalDigests()),
                Long.toString(binding.authorizationGeneration()), binding.traceId(),
                issuedAt.toString(), authorizedUntil.toString(), audience);
        if (binding.observationDecisionDigest() == null) return predecessor;
        return String.join("\n", predecessor, binding.observationDecisionDigest(),
                binding.memberSetDigest(), binding.watermarksDigest(),
                binding.qualityRecoveryPolicyVersion(),
                binding.qualityRecoveryPolicyDigest());
    }
}
