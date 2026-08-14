package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Identity-access-owned D4 maker/checker lifecycle. */
public final class HighRiskApproval {
    public static final Duration PENDING_DURATION = Duration.ofHours(4);

    private final UUID approvalId;
    private final HighRiskApprovalBinding binding;
    private final Instant requestedAt;
    private final Instant expiresAt;
    private final TreeSet<String> approvedCheckerDigests;
    private long approvalVersion;
    private HighRiskApprovalStatus status;
    private String decisionActorPrincipalDigest;
    private Instant decidedAt;

    private HighRiskApproval(
            UUID approvalId, HighRiskApprovalBinding binding, Instant requestedAt) {
        HighRiskApprovalBinding.requireUuidV7(approvalId);
        if (binding == null || !micros(requestedAt)) throw HighRiskApprovalBinding.invalid();
        this.approvalId = approvalId;
        this.binding = binding;
        this.requestedAt = requestedAt;
        this.expiresAt = requestedAt.plus(PENDING_DURATION);
        this.approvalVersion = 1;
        this.status = HighRiskApprovalStatus.PENDING;
        this.approvedCheckerDigests = new TreeSet<>();
    }

    public static HighRiskApproval pending(
            UUID approvalId, HighRiskApprovalBinding binding, Instant requestedAt) {
        return new HighRiskApproval(approvalId, binding, requestedAt);
    }

    /** Rehydrates an owner-persisted aggregate without replaying lifecycle commands. */
    public static HighRiskApproval restore(
            UUID approvalId,
            HighRiskApprovalBinding binding,
            Instant requestedAt,
            long approvalVersion,
            HighRiskApprovalStatus status,
            Set<String> approvedCheckerDigests,
            String decisionActorPrincipalDigest,
            Instant decidedAt) {
        HighRiskApproval value = new HighRiskApproval(approvalId, binding, requestedAt);
        if (approvalVersion < 1 || status == null || approvedCheckerDigests == null
                || approvedCheckerDigests.size() > binding.requiredCheckerPrincipalDigests().size()
                || !binding.requiredCheckerPrincipalDigests().containsAll(approvedCheckerDigests)
                || status == HighRiskApprovalStatus.PENDING && decidedAt != null
                || status != HighRiskApprovalStatus.PENDING && decidedAt == null
                || decidedAt != null && (!micros(decidedAt) || decidedAt.isBefore(requestedAt))
                || decisionActorPrincipalDigest != null
                        && !decisionActorPrincipalDigest.matches("sha256:[0-9a-f]{64}")) {
            throw HighRiskApprovalBinding.invalid();
        }
        boolean approvedShape = status == HighRiskApprovalStatus.APPROVED
                && approvedCheckerDigests.equals(Set.copyOf(
                        binding.requiredCheckerPrincipalDigests()));
        boolean pendingShape = status == HighRiskApprovalStatus.PENDING
                && !approvedCheckerDigests.equals(Set.copyOf(
                        binding.requiredCheckerPrincipalDigests()));
        if (!approvedShape && !pendingShape
                && status != HighRiskApprovalStatus.REJECTED
                && status != HighRiskApprovalStatus.EXPIRED
                && status != HighRiskApprovalStatus.CANCELLED) {
            throw HighRiskApprovalBinding.invalid();
        }
        value.approvalVersion = approvalVersion;
        value.status = status;
        value.approvedCheckerDigests.addAll(approvedCheckerDigests);
        value.decisionActorPrincipalDigest = decisionActorPrincipalDigest;
        value.decidedAt = decidedAt;
        return value;
    }

    public void approve(String checkerPrincipalDigest, Instant trustedNow) {
        requirePendingAt(trustedNow);
        requireChecker(checkerPrincipalDigest);
        if (!approvedCheckerDigests.add(checkerPrincipalDigest)) throw invalidState();
        approvalVersion++;
        decisionActorPrincipalDigest = checkerPrincipalDigest;
        decidedAt = trustedNow;
        if (approvedCheckerDigests.equals(Set.copyOf(
                binding.requiredCheckerPrincipalDigests()))) {
            status = HighRiskApprovalStatus.APPROVED;
        }
    }

    public void reject(String checkerPrincipalDigest, Instant trustedNow) {
        requirePendingAt(trustedNow);
        requireChecker(checkerPrincipalDigest);
        status = HighRiskApprovalStatus.REJECTED;
        decisionActorPrincipalDigest = checkerPrincipalDigest;
        decidedAt = trustedNow;
        approvalVersion++;
    }

    public void cancel(String actorPrincipalDigest, Instant trustedNow) {
        requirePendingAt(trustedNow);
        HighRiskApprovalBinding.requireDigest(actorPrincipalDigest);
        if (!binding.makerPrincipalDigest().equals(actorPrincipalDigest)) throw invalidState();
        status = HighRiskApprovalStatus.CANCELLED;
        decisionActorPrincipalDigest = actorPrincipalDigest;
        decidedAt = trustedNow;
        approvalVersion++;
    }

    public void expire(Instant trustedNow) {
        if ((status != HighRiskApprovalStatus.PENDING
                    && status != HighRiskApprovalStatus.APPROVED) || !micros(trustedNow)
                || trustedNow.isBefore(expiresAt)) throw invalidState();
        status = HighRiskApprovalStatus.EXPIRED;
        decidedAt = trustedNow;
        approvalVersion++;
    }

    public boolean mayIssueExecutionToken(Instant trustedNow) {
        return status == HighRiskApprovalStatus.APPROVED && micros(trustedNow)
                && !trustedNow.isBefore(requestedAt) && trustedNow.isBefore(expiresAt);
    }

    private void requirePendingAt(Instant trustedNow) {
        if (status != HighRiskApprovalStatus.PENDING || !micros(trustedNow)
                || trustedNow.isBefore(requestedAt) || !trustedNow.isBefore(expiresAt)) {
            throw invalidState();
        }
    }

    private void requireChecker(String principalDigest) {
        HighRiskApprovalBinding.requireDigest(principalDigest);
        if (binding.makerPrincipalDigest().equals(principalDigest)
                || !binding.requiredCheckerPrincipalDigests().contains(principalDigest)) {
            throw invalidState();
        }
    }

    private static boolean micros(Instant value) {
        return value != null && value.getNano() % 1_000 == 0;
    }

    private static IllegalStateException invalidState() {
        return new IllegalStateException("HIGH_RISK_APPROVAL_STATE_INVALID");
    }

    public UUID approvalId() { return approvalId; }
    public long approvalVersion() { return approvalVersion; }
    public HighRiskApprovalBinding binding() { return binding; }
    public HighRiskApprovalStatus status() { return status; }
    public Instant requestedAt() { return requestedAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant decidedAt() { return decidedAt; }
    public String decisionActorPrincipalDigest() { return decisionActorPrincipalDigest; }
    public Set<String> approvedCheckerDigests() { return Set.copyOf(approvedCheckerDigests); }
}
