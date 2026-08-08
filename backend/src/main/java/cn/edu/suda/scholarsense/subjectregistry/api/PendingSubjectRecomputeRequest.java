package cn.edu.suda.scholarsense.subjectregistry.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Minimal non-sensitive request state exposed while the committed outbox awaits relay. */
public record PendingSubjectRecomputeRequest(
        UUID requestId,
        String ownerSourceId,
        Instant queuedAt,
        String traceId) {
    public PendingSubjectRecomputeRequest {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(queuedAt);
        if (ownerSourceId == null
                || !ownerSourceId.matches("^SRC-P[01]-[A-Z-]+-[0-9]{3}$")
                || traceId == null || !traceId.matches("^[0-9a-f]{32}$")) {
            throw new IllegalArgumentException("SUBJECT_RECOMPUTE_REQUEST_INVALID");
        }
    }
}
