package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One relation-level source-visible to public scope read-back observation. */
public record ResponsibilitySloEvidence(
        UUID evidenceId,
        UUID relationId,
        String studentSourceRefDigest,
        long sourceVersion,
        long sourceWatermark,
        long aggregateVersion,
        Instant sourceVisibleAt,
        Instant appliedAt,
        Instant authorizationEffectiveAt,
        boolean withinFifteenMinutes,
        String lateReasonCode,
        String traceId) {
    public ResponsibilitySloEvidence {
        requireUuidV7(evidenceId);
        requireUuidV7(relationId);
        if (studentSourceRefDigest == null
                || !studentSourceRefDigest.matches("[0-9a-f]{64}")
                || sourceVersion < 1
                || sourceWatermark < 1
                || aggregateVersion < 1
                || traceId == null
                || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SLO_EVIDENCE_INVALID");
        }
        Objects.requireNonNull(sourceVisibleAt, "sourceVisibleAt");
        Objects.requireNonNull(appliedAt, "appliedAt");
        Objects.requireNonNull(
                authorizationEffectiveAt,
                "authorizationEffectiveAt");
        if (appliedAt.isBefore(sourceVisibleAt)
                || authorizationEffectiveAt.isBefore(appliedAt)
                || withinFifteenMinutes
                        != (lateReasonCode == null
                                && !authorizationEffectiveAt.isAfter(
                                        sourceVisibleAt.plus(
                                                Duration.ofMinutes(15))))
                || lateReasonCode != null
                        && !lateReasonCode.matches(
                                "RESPONSIBILITY_[A-Z0-9_]+")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SLO_EVIDENCE_INVALID");
        }
    }

    public ResponsibilitySloEvidence asWriteFailure() {
        return new ResponsibilitySloEvidence(
                evidenceId,
                relationId,
                studentSourceRefDigest,
                sourceVersion,
                sourceWatermark,
                aggregateVersion,
                sourceVisibleAt,
                appliedAt,
                authorizationEffectiveAt,
                false,
                "RESPONSIBILITY_SLO_EVIDENCE_WRITE_FAILED",
                traceId);
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SLO_UUIDV7_REQUIRED");
        }
    }
}
