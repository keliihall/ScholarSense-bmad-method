package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.UUID;

/** Story 2.5-owned command contract; Story 2.4 deliberately installs no runtime adapter. */
public record QualityEligibilityRecoveryCommand(
        UUID eligibilityId,
        long expectedAggregateVersion,
        String idempotencyKey,
        String reasonCode,
        String traceId) {
    public QualityEligibilityRecoveryCommand {
        if (eligibilityId == null || eligibilityId.version() != 7 || eligibilityId.variant() != 2
                || expectedAggregateVersion < 1
                || idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128
                || reasonCode == null || !reasonCode.matches("^[A-Z][A-Z0-9_]{2,127}$")
                || traceId == null || !traceId.matches("^(?!0{32}$)[0-9a-f]{32}$")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_RECOVERY_COMMAND_INVALID");
        }
    }
}
