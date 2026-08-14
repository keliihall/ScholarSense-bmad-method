package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskApproval;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Identity-access transaction adapter supplies atomic CAS and idempotency. */
public interface HighRiskApprovalRepositoryPort {
    record DecisionReplay(
            String inputDigest,
            HighRiskApproval approval,
            String receiptDigest) {}

    Optional<HighRiskApproval> findByIdempotencyKeyDigest(String idempotencyKeyDigest);

    Optional<HighRiskApproval> findById(UUID approvalId);

    HighRiskApproval insertIfAbsent(String idempotencyKeyDigest, HighRiskApproval requested);

    HighRiskApproval save(
            long expectedApprovalVersion,
            HighRiskApproval updated,
            HighRiskApprovalReceipt terminalReceipt);

    Optional<DecisionReplay> findDecisionByIdempotencyKeyDigest(
            String idempotencyKeyDigest);

    DecisionReplay saveDecisionIfAbsent(
            String idempotencyKeyDigest,
            String inputDigest,
            long expectedApprovalVersion,
            HighRiskApproval updated,
            HighRiskApprovalReceipt terminalReceipt);

    Optional<HighRiskApprovalReceipt> findReceipt(UUID approvalId);

    java.util.List<HighRiskApproval> findExpirable(
            int limit, java.time.Instant trustedNow);

    /** Keeps the expirable-row claim locks alive until every corresponding CAS save commits. */
    default <T> T inExpiryTransaction(Supplier<T> action) {
        return action.get();
    }
}
