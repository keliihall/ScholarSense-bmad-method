package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApproval;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApprovalBinding;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApprovalStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskExecutionAuthorizationLease;
import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskExecutionToken;
import java.time.Instant;
import java.util.Objects;

/** Issues exactly one executionJti per approval and reconciles owner commits idempotently. */
public final class HighRiskExecutionAuthorizationUseCase {
    private final ApprovedHighRiskEvidenceQueryPort approvals;
    private final HighRiskExecutionLeaseRepositoryPort leases;
    private final HighRiskIdentityFactoryPort identities;
    private final HighRiskEvidenceSignaturePort signatures;
    private final HighRiskTrustedTimePort time;

    public HighRiskExecutionAuthorizationUseCase(
            ApprovedHighRiskEvidenceQueryPort approvals,
            HighRiskExecutionLeaseRepositoryPort leases,
            HighRiskIdentityFactoryPort identities,
            HighRiskEvidenceSignaturePort signatures,
            HighRiskTrustedTimePort time) {
        this.approvals = Objects.requireNonNull(approvals);
        this.leases = Objects.requireNonNull(leases);
        this.identities = Objects.requireNonNull(identities);
        this.signatures = Objects.requireNonNull(signatures);
        this.time = Objects.requireNonNull(time);
    }

    public HighRiskExecutionAuthorizationResult issueOrReplay(
            HighRiskExecutionAuthorizationCommand request) {
        Objects.requireNonNull(request);
        HighRiskExecutionLeaseRepositoryPort.LeaseReplay prior = leases
                .findByIdempotencyKeyDigest(request.idempotencyKeyDigest()).orElse(null);
        if (prior != null) {
            if (prior.lease().state()
                    != cn.edu.suda.scholarsense.identityaccess.domain
                            .HighRiskExecutionLeaseState.EXPIRED
                    && prior.lease().state()
                    != cn.edu.suda.scholarsense.identityaccess.domain
                            .HighRiskExecutionLeaseState.CANCELLED) {
                return replay(prior, request.issuanceRequestDigest());
            }
            leases.retireTerminal(prior.lease().leaseId(), prior.lease().leaseVersion(),
                    prior.lease().leaseDigest());
        }

        ApprovedHighRiskEvidenceQueryPort.ApprovedEvidence evidence = approvals
                .findApproved(request.approvalId())
                .orElseThrow(() -> new IllegalStateException("HIGH_RISK_APPROVAL_UNAVAILABLE"));
        HighRiskApproval approval = evidence.approval();
        requireCurrent(approval, evidence.receipt(), request);
        HighRiskExecutionAuthorizationLease approvalWinner = leases
                .findByApprovalId(request.approvalId()).orElse(null);
        if (approvalWinner != null) {
            if (approvalWinner.state()
                    != cn.edu.suda.scholarsense.identityaccess.domain
                            .HighRiskExecutionLeaseState.EXPIRED
                    && approvalWinner.state()
                    != cn.edu.suda.scholarsense.identityaccess.domain
                            .HighRiskExecutionLeaseState.CANCELLED) {
                return projection(approvalWinner);
            }
            leases.retireTerminal(approvalWinner.leaseId(), approvalWinner.leaseVersion(),
                    approvalWinner.leaseDigest());
        }
        java.util.UUID tokenJti = identities.nextUuidV7();
        Instant authorizedUntil = request.trustedNow().plus(
                HighRiskExecutionToken.AUTHORIZATION_DURATION);
        HighRiskExecutionToken unsignedToken = new HighRiskExecutionToken(
                tokenJti, approval.approvalId(), approval.approvalVersion(),
                evidence.receipt().receiptDigest(), approval.binding(), request.trustedNow(),
                authorizedUntil, request.audience(), "pending", "A".repeat(43));
        HighRiskEvidenceSignaturePort.SignedValue tokenSignature = signatures.sign(
                unsignedToken.canonicalUnsigned());
        HighRiskExecutionToken token = new HighRiskExecutionToken(
                tokenJti, approval.approvalId(), approval.approvalVersion(),
                evidence.receipt().receiptDigest(), approval.binding(), request.trustedNow(),
                authorizedUntil,
                request.audience(), tokenSignature.keyVersion(), tokenSignature.signature());
        java.util.UUID leaseId = identities.nextUuidV7();
        java.util.UUID executionJti = identities.nextUuidV7();
        HighRiskExecutionAuthorizationLease unsignedLease =
                HighRiskExecutionAuthorizationLease.issued(
                        leaseId, digest("lease-binding-pending"), executionJti, token,
                        tokenSignature.keyVersion(), "A".repeat(43));
        String leaseMaterial = unsignedLease.canonicalUnsigned();
        HighRiskEvidenceSignaturePort.SignedValue leaseSignature = signatures.sign(leaseMaterial);
        HighRiskExecutionAuthorizationLease requested =
                HighRiskExecutionAuthorizationLease.issued(
                        leaseId, leaseSignature.digest(), executionJti, token,
                        leaseSignature.keyVersion(), leaseSignature.signature());
        return replay(leases.issueIfAbsent(
                request.idempotencyKeyDigest(), request.issuanceRequestDigest(), requested),
                request.issuanceRequestDigest());
    }

    public HighRiskExecutionAuthorizationResult reserve(
            HighRiskExecutionAuthorizationResult authorization, Instant trustedNow) {
        HighRiskExecutionAuthorizationLease lease = leases.findById(authorization.leaseId())
                .orElseThrow(() -> new IllegalStateException("HIGH_RISK_LEASE_NOT_FOUND"));
        requireProjection(lease, authorization);
        long expected = lease.leaseVersion();
        lease.reserve(trustedNow);
        return projection(leases.save(expected, lease));
    }

    public boolean verify(HighRiskExecutionAuthorizationResult authorization) {
        try {
            HighRiskExecutionAuthorizationLease lease = leases.findById(
                    authorization.leaseId()).orElse(null);
            if (lease == null) return false;
            requireProjection(lease, authorization);
            String tokenCanonical = lease.token().canonicalUnsigned();
            if (!signatures.verify(tokenCanonical, lease.token().keyVersion(),
                    lease.token().signature(), digest(tokenCanonical))) return false;
            String leaseMaterial = lease.canonicalUnsigned();
            return signatures.verify(leaseMaterial, lease.keyVersion(), lease.signature(),
                    lease.leaseDigest());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public int expireDue(Instant trustedNow, int limit) {
        if (trustedNow == null || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("HIGH_RISK_EXECUTION_EXPIRY_BATCH_INVALID");
        }
        return leases.inExpiryTransaction(() -> {
            int expired = 0;
            for (HighRiskExecutionAuthorizationLease lease
                    : leases.findExpirable(limit, trustedNow)) {
                long expected = lease.leaseVersion();
                lease.expire(trustedNow);
                leases.save(expected, lease);
                expired++;
            }
            return expired;
        });
    }

    public HighRiskExecutionAuthorizationResult reconcile(
            HighRiskExecutionReconciliationCommand reconciliation) {
        Objects.requireNonNull(reconciliation);
        if (!canonicalReconciliationDigest(reconciliation)
                .equals(reconciliation.reconciliationDigest())) {
            throw new IllegalStateException("HIGH_RISK_RECONCILIATION_DIGEST_INVALID");
        }
        HighRiskExecutionAuthorizationLease lease = leases.findById(reconciliation.leaseId())
                .orElseThrow(() -> new IllegalStateException("HIGH_RISK_LEASE_NOT_FOUND"));
        if (lease.state()
                == cn.edu.suda.scholarsense.identityaccess.domain.HighRiskExecutionLeaseState.EXECUTED) {
            if (!Objects.equals(lease.ownerCommitId(), reconciliation.ownerCommitId())
                    || !Objects.equals(lease.outboxEventId(), reconciliation.outboxEventId())
                    || !Objects.equals(lease.ownerResultDigest(),
                            reconciliation.ownerResultDigest())) {
                throw new IllegalStateException("HIGH_RISK_RECONCILIATION_CONFLICT");
            }
            return projection(lease);
        }
        boolean currentOrJustExpiredVersion =
                lease.leaseVersion() == reconciliation.leaseVersion()
                || lease.state()
                    == cn.edu.suda.scholarsense.identityaccess.domain
                            .HighRiskExecutionLeaseState.EXPIRED
                    && lease.leaseVersion() == reconciliation.leaseVersion() + 1;
        if (!currentOrJustExpiredVersion
                || !lease.leaseDigest().equals(reconciliation.leaseDigest())
                || !lease.executionJti().equals(reconciliation.executionJti())
                || !lease.token().binding().requestDigest()
                        .equals(reconciliation.requestDigest())
                || !lease.token().binding().traceId().equals(reconciliation.traceId())) {
            throw new IllegalStateException("HIGH_RISK_RECONCILIATION_BINDING_DRIFT");
        }
        long expected = lease.leaseVersion();
        lease.confirmOwnerCommit(
                reconciliation.ownerCommitId(), reconciliation.ownerCommittedAt(),
                reconciliation.ownerResultDigest(), reconciliation.outboxEventId(),
                trustedNow());
        return projection(leases.save(expected, lease));
    }

    private static void requireCurrent(
            HighRiskApproval approval,
            HighRiskApprovalReceipt receipt,
            HighRiskExecutionAuthorizationCommand request) {
        HighRiskApprovalBinding binding = approval.binding();
        if (approval.status() != HighRiskApprovalStatus.APPROVED
                || !approval.mayIssueExecutionToken(request.trustedNow())
                || approval.approvalVersion() != request.approvalVersion()
                || !receipt.receiptDigest().equals(request.approvalReceiptDigest())
                || !binding.requestDigest().equals(request.requestDigest())
                || !binding.actionType().equals(request.actionType())
                || !binding.objectType().equals(request.objectType())
                || !binding.objectRefDigest().equals(request.objectRefDigest())
                || binding.objectVersion() != request.objectVersion()
                || !binding.scopeDigest().equals(request.scopeDigest())
                || !binding.impactScopeDigest().equals(request.impactScopeDigest())
                || !binding.previewDigest().equals(request.previewDigest())
                || !binding.authorizationContextDigest()
                        .equals(request.authorizationContextDigest())
                || !binding.authenticationStateDigest()
                        .equals(request.authenticationStateDigest())
                || !binding.checkerSetDigest().equals(request.checkerSetDigest())
                || binding.authorizationGeneration() != request.authorizationGeneration()
                || !binding.traceId().equals(request.traceId())) {
            throw new IllegalStateException("HIGH_RISK_EXECUTION_BINDING_DRIFT");
        }
    }

    private static HighRiskExecutionAuthorizationResult replay(
            HighRiskExecutionLeaseRepositoryPort.LeaseReplay replay, String requestDigest) {
        if (!replay.issuanceRequestDigest().equals(requestDigest)) {
            throw new IllegalStateException("HIGH_RISK_EXECUTION_IDEMPOTENCY_CONFLICT");
        }
        return projection(replay.lease());
    }

    private static void requireProjection(
            HighRiskExecutionAuthorizationLease lease,
            HighRiskExecutionAuthorizationResult value) {
        if (!lease.leaseId().equals(value.leaseId())
                || lease.leaseVersion() != value.leaseVersion()
                || !lease.leaseDigest().equals(value.leaseDigest())
                || !lease.executionJti().equals(value.executionJti())) {
            throw new IllegalStateException("HIGH_RISK_LEASE_BINDING_DRIFT");
        }
    }

    private static HighRiskExecutionAuthorizationResult projection(
            HighRiskExecutionAuthorizationLease value) {
        HighRiskExecutionToken token = value.token();
        HighRiskApprovalBinding binding = token.binding();
        return new HighRiskExecutionAuthorizationResult(
                value.leaseId(), value.leaseVersion(), value.leaseDigest(), value.executionJti(),
                token.approvalId(), token.approvalVersion(), token.approvalReceiptDigest(),
                binding.requestDigest(), binding.actionType(), binding.objectType(),
                binding.objectRefDigest(), binding.objectVersion(), binding.scopeDigest(),
                binding.impactScopeDigest(), binding.previewDigest(),
                binding.authorizationGeneration(), value.state().name().toLowerCase(),
                token.issuedAt(), token.authorizedUntil(), "identity-access", token.audience(),
                value.keyVersion(), value.signature(), binding.traceId());
    }

    private Instant trustedNow() {
        Instant value = Objects.requireNonNull(time.now());
        if (value.getNano() % 1_000 != 0) {
            throw new IllegalStateException("HIGH_RISK_TRUSTED_TIME_INVALID");
        }
        return value;
    }

    public static String canonicalReconciliationDigest(
            HighRiskExecutionReconciliationCommand value) {
        String canonical = String.join("\n", value.leaseId().toString(),
                Long.toString(value.leaseVersion()), value.leaseDigest(),
                value.executionJti().toString(), value.requestDigest(), value.ownerCommitId(),
                value.ownerCommittedAt().toString(), value.ownerResultDigest(),
                value.outboxEventId().toString(), value.traceId());
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(
                            canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
        }
    }

    private static String digest(String value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
        }
    }
}
