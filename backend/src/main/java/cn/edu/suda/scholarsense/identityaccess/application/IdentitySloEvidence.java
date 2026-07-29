package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IdentitySloEvidence(
        UUID evidenceId,
        UUID accountId,
        IdentityRecordKind recordKind,
        long sourceVersion,
        long sourceWatermark,
        long aggregateVersion,
        Instant sourceVisibleAt,
        Instant appliedAt,
        Instant authorizationEffectiveAt,
        boolean withinFifteenMinutes,
        String lateReasonCode,
        String traceId) {

    public IdentitySloEvidence {
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(recordKind, "recordKind");
        Objects.requireNonNull(sourceVisibleAt, "sourceVisibleAt");
        Objects.requireNonNull(appliedAt, "appliedAt");
        Objects.requireNonNull(authorizationEffectiveAt, "authorizationEffectiveAt");
        if (accountId == null
                && !"IDENTITY_AUTHORIZATION_READBACK_EMPTY".equals(lateReasonCode)) {
            throw new IllegalArgumentException("IDENTITY_SLO_ACCOUNT_REQUIRED");
        }
        if (accountId != null
                && "IDENTITY_AUTHORIZATION_READBACK_EMPTY".equals(lateReasonCode)) {
            throw new IllegalArgumentException("IDENTITY_SLO_EMPTY_ACCOUNT_INVALID");
        }
    }
}
