package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.LocalDate;
import java.util.Objects;

/** Operator request containing only route/date identity, never expected digests. */
public record ResponsibilityV2ReconciliationRequest(
        CheckpointKey key,
        LocalDate businessDate,
        String traceId) {
    public ResponsibilityV2ReconciliationRequest {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(businessDate, "businessDate");
        if (!"responsibility".equals(key.consumerProjection())
                || traceId == null
                || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_V2_RECONCILIATION_REQUEST_INVALID");
        }
    }
}
