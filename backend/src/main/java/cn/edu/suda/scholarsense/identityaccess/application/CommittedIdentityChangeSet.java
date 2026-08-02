package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Objects;

/** Identity changes known to have reached the core transaction boundary. */
public record CommittedIdentityChangeSet(
        NormalizedIdentityBatch batch,
        IdentitySyncResult result,
        Instant committedAt) {
    public CommittedIdentityChangeSet {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(committedAt, "committedAt");
        if (result.outcome() != IdentitySyncOutcome.APPLIED) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_CHANGE_SET_NOT_APPLIED");
        }
    }
}
