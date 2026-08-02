package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Responsibility changes known to have reached the core transaction boundary. */
public record CommittedResponsibilityChangeSet(
        NormalizedResponsibilityBatch batch,
        IdentitySyncResult result,
        List<ResponsibilityScopeProjectionUpdate> scopeUpdates,
        Instant committedAt) {
    public CommittedResponsibilityChangeSet {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(result, "result");
        scopeUpdates = List.copyOf(scopeUpdates);
        Objects.requireNonNull(committedAt, "committedAt");
        if (result.outcome() != IdentitySyncOutcome.APPLIED) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_CHANGE_SET_NOT_APPLIED");
        }
    }
}
