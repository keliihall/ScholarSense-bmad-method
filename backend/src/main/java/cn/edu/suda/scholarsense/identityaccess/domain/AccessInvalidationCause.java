package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Instant;
import java.util.UUID;

public record AccessInvalidationCause(
        UUID causeEventId,
        AccessInvalidationLineageId causeLineageId,
        AccessInvalidationReason reasonCode,
        AccessInvalidationSourceVector sourceVector,
        Instant effectiveAt,
        String traceId) {
    public AccessInvalidationCause {
        AccessInvalidationValidation.uuidV7(
                causeEventId, "ACCESS_INVALIDATION_CAUSE");
        AccessInvalidationValidation.required(
                causeLineageId, "causeLineageId");
        AccessInvalidationValidation.required(reasonCode, "reasonCode");
        if (reasonCode
                        != AccessInvalidationReason.ACCOUNT_DISABLED
                && reasonCode
                        != AccessInvalidationReason.R1_EMPLOYMENT_INVALID
                && reasonCode
                        != AccessInvalidationReason.COLLEGE_INVALID
                && reasonCode
                        != AccessInvalidationReason.SOURCE_CORRECTION) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_CAUSE_REASON_INVALID");
        }
        AccessInvalidationValidation.required(
                sourceVector, "sourceVector");
        AccessInvalidationValidation.required(effectiveAt, "effectiveAt");
        AccessInvalidationValidation.traceId(traceId);
    }
}
