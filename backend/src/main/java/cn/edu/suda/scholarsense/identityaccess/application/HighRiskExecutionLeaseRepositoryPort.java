package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.HighRiskExecutionAuthorizationLease;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface HighRiskExecutionLeaseRepositoryPort {
    Optional<LeaseReplay> findByIdempotencyKeyDigest(String digest);

    Optional<HighRiskExecutionAuthorizationLease> findById(UUID leaseId);

    Optional<HighRiskExecutionAuthorizationLease> findByApprovalId(UUID approvalId);

    LeaseReplay issueIfAbsent(
            String idempotencyKeyDigest,
            String issuanceRequestDigest,
            HighRiskExecutionAuthorizationLease requested);

    HighRiskExecutionAuthorizationLease save(
            long expectedLeaseVersion, HighRiskExecutionAuthorizationLease updated);

    default java.util.List<HighRiskExecutionAuthorizationLease> findExpirable(
            int limit, java.time.Instant trustedNow) {
        return java.util.List.of();
    }

    default void retireTerminal(
            UUID leaseId, long expectedLeaseVersion, String expectedLeaseDigest) {
        throw new UnsupportedOperationException("HIGH_RISK_EXECUTION_RETIRE_UNSUPPORTED");
    }

    default <T> T inExpiryTransaction(Supplier<T> action) {
        return action.get();
    }

    record LeaseReplay(
            String issuanceRequestDigest,
            HighRiskExecutionAuthorizationLease lease) {}
}
