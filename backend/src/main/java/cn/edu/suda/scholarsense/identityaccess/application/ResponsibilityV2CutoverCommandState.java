package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.UUID;

/** Immutable readback of the durable cutover command attempt ledger. */
public record ResponsibilityV2CutoverCommandState(
        UUID commandId,
        String commandDigest,
        String status,
        String reasonCode,
        UUID snapshotId,
        Instant completedAt) {
    public ResponsibilityV2CutoverCommandState {
        if (commandId == null
                || commandDigest == null
                || !commandDigest.matches("[0-9a-f]{64}")
                || !java.util.Set.of(
                                "requested", "denied", "failed",
                                "activated")
                        .contains(status)
                || reasonCode != null
                        && !reasonCode.matches("[A-Z][A-Z0-9_]{2,127}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_V2_CUTOVER_COMMAND_STATE_INVALID");
        }
    }
}
