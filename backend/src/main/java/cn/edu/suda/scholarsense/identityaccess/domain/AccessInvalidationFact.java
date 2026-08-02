package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Instant;
import java.util.UUID;

/** Immutable self-contained authorization invalidation or recovery fact. */
public record AccessInvalidationFact(
        UUID eventId,
        String traceId,
        AccessInvalidationChangeKind changeKind,
        AccessInvalidationReason reasonCode,
        AccessInvalidationLineageId lineageId,
        UUID supersedesId,
        UUID causeEventId,
        AccessInvalidationAggregateType aggregateType,
        String aggregateId,
        long aggregateVersion,
        long invalidationVersion,
        Instant effectiveAt,
        AccessInvalidationSourceVector sourceVector,
        AccessInvalidationSubjectSnapshot subjectSnapshot,
        AccessInvalidationAuthorizationSnapshot authorizationSnapshot,
        AccessInvalidationRetention retention,
        String payloadDigest) {
    public AccessInvalidationFact {
        AccessInvalidationValidation.uuidV7(
                eventId, "ACCESS_INVALIDATION_EVENT");
        AccessInvalidationValidation.traceId(traceId);
        AccessInvalidationValidation.required(changeKind, "changeKind");
        AccessInvalidationValidation.required(reasonCode, "reasonCode");
        AccessInvalidationValidation.required(lineageId, "lineageId");
        if (supersedesId != null) {
            AccessInvalidationValidation.uuidV7(
                    supersedesId, "ACCESS_INVALIDATION_SUPERSEDES");
        }
        if (causeEventId != null) {
            AccessInvalidationValidation.uuidV7(
                    causeEventId, "ACCESS_INVALIDATION_CAUSE");
        }
        AccessInvalidationValidation.required(
                aggregateType, "aggregateType");
        if (aggregateType
                == AccessInvalidationAggregateType.RESPONSIBILITY_SCOPE) {
            if (!lineageId.value().equals(aggregateId)) {
                throw new IllegalArgumentException(
                        "ACCESS_INVALIDATION_AGGREGATE_LINEAGE_MISMATCH");
            }
        } else {
            AccessInvalidationValidation.token(
                    aggregateId,
                    "cause_",
                    "ACCESS_INVALIDATION_CAUSE_AGGREGATE");
            if (causeEventId != null) {
                throw new IllegalArgumentException(
                        "ACCESS_INVALIDATION_CAUSE_NESTING_INVALID");
            }
        }
        AccessInvalidationValidation.positive(
                aggregateVersion,
                "ACCESS_INVALIDATION_AGGREGATE_VERSION");
        AccessInvalidationValidation.positive(
                invalidationVersion,
                "ACCESS_INVALIDATION_VERSION");
        if (aggregateVersion != invalidationVersion) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_VERSION_MISMATCH");
        }
        if ((aggregateVersion == 1) != (supersedesId == null)) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_SUPERSEDES_SEQUENCE_INVALID");
        }
        AccessInvalidationValidation.required(effectiveAt, "effectiveAt");
        AccessInvalidationValidation.required(
                sourceVector, "sourceVector");
        AccessInvalidationValidation.required(
                subjectSnapshot, "subjectSnapshot");
        AccessInvalidationValidation.required(
                authorizationSnapshot, "authorizationSnapshot");
        AccessInvalidationValidation.required(retention, "retention");
        if (!retention.retainUntil().isAfter(effectiveAt)) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_RETENTION_BOUNDARY_INVALID");
        }
        AccessInvalidationValidation.digest(
                payloadDigest, "ACCESS_INVALIDATION_PAYLOAD");
        if (changeKind == AccessInvalidationChangeKind.REVALIDATED) {
            if (reasonCode
                            != AccessInvalidationReason
                                    .RECONCILIATION_RECOVERED
                    || authorizationSnapshot.currentState()
                            != AccessInvalidationAuthorizationState
                                    .REVALIDATED) {
                throw new IllegalArgumentException(
                        "ACCESS_INVALIDATION_RECOVERY_TAXONOMY_INVALID");
            }
        } else if (authorizationSnapshot.currentState()
                != AccessInvalidationAuthorizationState.INVALIDATED) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_STATE_KIND_MISMATCH");
        }
        if (changeKind == AccessInvalidationChangeKind.EXPIRED
                && reasonCode
                        != AccessInvalidationReason.RELATION_EXPIRED) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_EXPIRY_REASON_INVALID");
        }
    }
}
