package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.UUID;

/** Public final command contains opaque identity, optimistic versions and an idempotency key only. */
public record QualityRecoveryFinalizationCommand(
        UUID recoveryId,
        long expectedRecoveryVersion,
        long expectedTaskVersion,
        String finalObservationWatermark,
        String idempotencyKey) {
    public QualityRecoveryFinalizationCommand {
        if (recoveryId == null || recoveryId.version() != 7 || recoveryId.variant() != 2
                || expectedRecoveryVersion < 1 || expectedTaskVersion < 1
                || finalObservationWatermark == null || finalObservationWatermark.isBlank()
                || finalObservationWatermark.length() > 256
                || idempotencyKey == null
                || !idempotencyKey.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new IllegalArgumentException("QUALITY_FINALIZATION_COMMAND_INVALID");
        }
    }
}
