package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Closed database commands; no caller receives table-level access. */
public interface QualityRecoveryCommandStorePort {
    Optional<QualityRecoveryCommandContext> loadContext(UUID taskId);
    Optional<QualityRecoveryRequestState> findRequest(UUID recoveryRequestId);
    Optional<QualityRecoveryRequestState> findRequestReplay(
            String idempotencyKeyDigest,
            String inputDigest,
            UUID expectedRecoveryRequestId);
    Optional<QualityRecoveryExecutionCommit> findExecutionReplay(
            String idempotencyKeyDigest,
            String inputDigest,
            UUID expectedRecoveryRequestId);
    QualityRecoveryRequestState submitRequest(String idempotencyKeyDigest, Map<String, Object> value);
    QualityRecoveryRequestState submitRequestWithValidationJob(
            String requestIdempotencyKeyDigest,
            Map<String, Object> request,
            String jobIdempotencyKeyDigest,
            UUID jobId);
    QualityRecoveryRequestState submitValidationJob(
            String idempotencyKeyDigest, Map<String, Object> value);
    QualityRecoveryRequestState bindApproval(
            String idempotencyKeyDigest,
            String inputDigest,
            Map<String, Object> value,
            Instant trustedNow);
    QualityRecoveryExecutionCommit execute(
            String idempotencyKeyDigest, Map<String, Object> value);
}
