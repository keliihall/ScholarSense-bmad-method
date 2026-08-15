package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApproval;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApprovalBinding;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** D4 lifecycle service; repository implementations provide one owner-local transaction per call. */
public final class HighRiskApprovalUseCase {
    private static final String SYSTEM_EXPIRY_PRINCIPAL_DIGEST =
            "sha256:ce8273fe4942361b045f9fb571e8c098ed04db9a88b5fadd318d66f2ebd64554";
    private final HighRiskApprovalRepositoryPort repository;
    private final HighRiskIdentityFactoryPort identities;
    private final HighRiskEvidenceSignaturePort signatures;

    public HighRiskApprovalUseCase(
            HighRiskApprovalRepositoryPort repository,
            HighRiskIdentityFactoryPort identities,
            HighRiskEvidenceSignaturePort signatures) {
        this.repository = Objects.requireNonNull(repository);
        this.identities = Objects.requireNonNull(identities);
        this.signatures = Objects.requireNonNull(signatures);
    }

    public HighRiskApprovalResult request(HighRiskApprovalCommand request) {
        Objects.requireNonNull(request);
        String keyDigest = digest(request.idempotencyKey());
        HighRiskApproval existing = repository.findByIdempotencyKeyDigest(keyDigest).orElse(null);
        HighRiskApprovalBinding binding = binding(request);
        if (existing != null) return replay(existing, binding.requestDigest());
        HighRiskApproval requested = HighRiskApproval.pending(
                identities.nextUuidV7(), binding, request.requestedAt());
        HighRiskApproval persisted = repository.insertIfAbsent(keyDigest, requested);
        return persisted == requested ? view(persisted, null)
                : replay(persisted, binding.requestDigest());
    }

    public HighRiskApprovalResult decide(HighRiskApprovalDecisionCommand decision) {
        Objects.requireNonNull(decision);
        String keyDigest = digest(decision.idempotencyKey());
        String inputDigest = decisionInputDigest(decision);
        HighRiskApprovalRepositoryPort.DecisionReplay replay = repository
                .findDecisionByIdempotencyKeyDigest(keyDigest).orElse(null);
        if (replay != null) return replayDecision(replay, inputDigest);
        HighRiskApproval approval = repository.findById(decision.approvalId())
                .orElseThrow(() -> new IllegalStateException("HIGH_RISK_APPROVAL_NOT_FOUND"));
        if (approval.approvalVersion() != decision.expectedApprovalVersion()
                || !approval.binding().checkerSetDigest()
                        .equals(decision.currentCheckerSetDigest())
                || approval.binding().authorizationGeneration()
                        != decision.currentAuthorizationGeneration()
                || !approval.binding().traceId().equals(decision.traceId())) {
            throw new IllegalStateException("HIGH_RISK_APPROVAL_BINDING_DRIFT");
        }
        long expected = approval.approvalVersion();
        switch (decision.decision()) {
            case APPROVE -> approval.approve(
                    decision.actorPrincipalDigest(), decision.trustedNow());
            case REJECT -> approval.reject(
                    decision.actorPrincipalDigest(), decision.trustedNow());
            case CANCEL -> approval.cancel(
                    decision.actorPrincipalDigest(), decision.trustedNow());
        }
        HighRiskApprovalReceipt receipt = approval.status()
                        == cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApprovalStatus.PENDING
                ? null : receipt(approval);
        HighRiskApprovalRepositoryPort.DecisionReplay saved =
                repository.saveDecisionIfAbsent(
                        keyDigest, inputDigest, expected, approval, receipt);
        return replayDecision(saved, inputDigest);
    }

    public int expireDue(Instant trustedNow, int limit) {
        if (limit < 1 || limit > 100 || trustedNow == null) {
            throw new IllegalArgumentException("HIGH_RISK_EXPIRY_BATCH_INVALID");
        }
        return repository.inExpiryTransaction(() -> {
            int expired = 0;
            for (HighRiskApproval approval : repository.findExpirable(limit, trustedNow)) {
                long expected = approval.approvalVersion();
                approval.expire(trustedNow);
                repository.save(expected, approval, receipt(approval));
                expired++;
            }
            return expired;
        });
    }

    private static HighRiskApprovalResult replay(
            HighRiskApproval existing, String requestDigest) {
        if (!existing.binding().requestDigest().equals(requestDigest)) {
            throw new IllegalStateException("HIGH_RISK_APPROVAL_IDEMPOTENCY_CONFLICT");
        }
        return view(existing, null);
    }

    private static HighRiskApprovalResult replayDecision(
            HighRiskApprovalRepositoryPort.DecisionReplay replay,
            String inputDigest) {
        if (!replay.inputDigest().equals(inputDigest)) {
            throw new IllegalStateException("HIGH_RISK_APPROVAL_IDEMPOTENCY_CONFLICT");
        }
        return view(replay.approval(), replay.receiptDigest());
    }

    private static String decisionInputDigest(HighRiskApprovalDecisionCommand value) {
        return digest(String.join("\n", value.approvalId().toString(),
                Long.toString(value.expectedApprovalVersion()), value.decision().name(),
                value.actorPrincipalDigest(), value.currentCheckerSetDigest(),
                Long.toString(value.currentAuthorizationGeneration()), value.traceId()));
    }

    private static HighRiskApprovalBinding binding(HighRiskApprovalCommand value) {
        return new HighRiskApprovalBinding(
                value.requestId(), value.requestDigest(), value.actionType(),
                value.makerPrincipalDigest(), value.authorizationContextDigest(),
                value.authenticationStateDigest(), value.objectType(), value.objectRefDigest(),
                value.objectVersion(), value.scopeDigest(), value.impactScopeDigest(),
                HighRiskApprovalBinding.DataSensitivity.valueOf(
                        value.dataSensitivity().replace('-', '_').toUpperCase()),
                value.currentState(), value.targetState(), value.reasonCode(),
                value.matrixVersion(), value.matrixDigest(), value.policyVersion(),
                value.policyDigest(), value.roleFieldPolicyVersion(),
                value.roleFieldPolicyDigest(), value.previewDigest(), value.checkerSetDigest(),
                value.requiredCheckerPrincipalDigests(), value.authorizationGeneration(),
                value.traceId(), value.observationDecisionDigest(), value.memberSetDigest(),
                value.watermarksDigest(), value.qualityRecoveryPolicyVersion(),
                value.qualityRecoveryPolicyDigest());
    }

    private static HighRiskApprovalResult view(
            HighRiskApproval value, String receiptDigest) {
        return new HighRiskApprovalResult(
                value.approvalId(), value.approvalVersion(), value.binding().requestId(),
                value.status().name().toLowerCase(),
                value.binding().requiredCheckerPrincipalDigests().size(),
                value.approvedCheckerDigests().size(), value.requestedAt(), value.expiresAt(),
                receiptDigest, value.binding().traceId());
    }

    private HighRiskApprovalReceipt receipt(HighRiskApproval value) {
        String canonical = String.join("\n", value.approvalId().toString(),
                Long.toString(value.approvalVersion()), value.binding().requestId().toString(),
                value.binding().requestDigest(), value.status().name(),
                value.decisionActorPrincipalDigest() == null
                        ? SYSTEM_EXPIRY_PRINCIPAL_DIGEST : value.decisionActorPrincipalDigest(),
                value.decidedAt().toString(), value.expiresAt().toString(),
                value.binding().traceId());
        HighRiskEvidenceSignaturePort.SignedValue signed = signatures.sign(canonical);
        return new HighRiskApprovalReceipt(
                value.approvalId(), value.approvalVersion(), value.binding().requestId(),
                value.binding().requestDigest(), value.status(),
                value.decisionActorPrincipalDigest() == null
                        ? SYSTEM_EXPIRY_PRINCIPAL_DIGEST : value.decisionActorPrincipalDigest(),
                value.decidedAt(), value.expiresAt(),
                signed.digest(), signed.keyVersion(), signed.signature(),
                value.binding().traceId());
    }

    private static String digest(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
        }
    }
}
