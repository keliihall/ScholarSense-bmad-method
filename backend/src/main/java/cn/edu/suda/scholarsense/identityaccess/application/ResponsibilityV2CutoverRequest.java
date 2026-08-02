package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.UUID;
import java.util.Objects;

/** Activates only the exact source-authenticated snapshot already held by the gate. */
public record ResponsibilityV2CutoverRequest(
        CheckpointKey key,
        UUID reconciliationSnapshotId,
        String traceId) {
    public ResponsibilityV2CutoverRequest {
        Objects.requireNonNull(key, "key");
        if (!"responsibility".equals(key.consumerProjection())
                || reconciliationSnapshotId == null
                || reconciliationSnapshotId.version() != 7
                || reconciliationSnapshotId.variant() != 2
                || traceId == null
                || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_V2_CUTOVER_REQUEST_INVALID");
        }
    }
}
