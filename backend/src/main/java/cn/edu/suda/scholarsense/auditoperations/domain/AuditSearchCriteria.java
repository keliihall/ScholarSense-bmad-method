package cn.edu.suda.scholarsense.auditoperations.domain;

import java.time.Instant;
import java.util.Set;

/** Typed and bounded query. Sensitive refs stay in memory and are tokenized outside this domain. */
public record AuditSearchCriteria(
        String requesterKey,
        AuditSearchView view,
        String actorRef,
        String objectType,
        String objectRef,
        String action,
        Instant occurredFrom,
        Instant occurredTo,
        String outcome,
        String traceId,
        int page,
        int size,
        Long asOfSequence,
        String requestTraceId) {
    public AuditSearchCriteria {
        require(requesterKey, 256, "AUDIT_SEARCH_REQUESTER_REQUIRED");
        if (view == null) throw new IllegalArgumentException("AUDIT_SEARCH_VIEW_REQUIRED");
        optional(actorRef, 256, "AUDIT_SEARCH_ACTOR_INVALID");
        optional(objectType, 64, "AUDIT_SEARCH_OBJECT_TYPE_INVALID");
        optional(objectRef, 256, "AUDIT_SEARCH_OBJECT_INVALID");
        if (objectRef != null && objectType == null) {
            throw new IllegalArgumentException("AUDIT_SEARCH_OBJECT_TYPE_REQUIRED");
        }
        if (action != null && !action.matches("[a-z][a-z0-9.-]{2,127}")) {
            throw new IllegalArgumentException("AUDIT_SEARCH_ACTION_INVALID");
        }
        if (occurredFrom != null && occurredTo != null && !occurredFrom.isBefore(occurredTo)) {
            throw new IllegalArgumentException("AUDIT_SEARCH_TIME_RANGE_INVALID");
        }
        if (outcome != null && !Set.of("accepted", "rejected", "failed", "succeeded").contains(outcome)) {
            throw new IllegalArgumentException("AUDIT_SEARCH_OUTCOME_INVALID");
        }
        if (traceId != null && !traceId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")) {
            throw new IllegalArgumentException("AUDIT_SEARCH_TRACE_INVALID");
        }
        if (page < 0 || size < 1 || size > 100 || asOfSequence != null && asOfSequence < 0) {
            throw new IllegalArgumentException("AUDIT_SEARCH_PAGE_INVALID");
        }
        require(requestTraceId, 128, "AUDIT_SEARCH_REQUEST_TRACE_REQUIRED");
    }

    private static void require(String value, int max, String code) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(code);
    }

    private static void optional(String value, int max, String code) {
        if (value != null && (value.isBlank() || value.length() > max)) throw new IllegalArgumentException(code);
    }
}
