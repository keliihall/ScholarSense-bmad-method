package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationJobKind;
import java.time.Instant;
import java.util.List;

public interface AccessInvalidationJobStorePort {
    List<AccessInvalidationJobLease> claim(
            AccessInvalidationJobKind kind,
            String leaseOwner,
            Instant now,
            int batchSize);

    boolean isCurrentExpiry(
            AccessInvalidationJobLease lease);

    /**
     * Locks the cause lineage head and verifies that this impact lease still
     * represents the current cause before a fan-out page is appended.
     */
    boolean isCurrentImpact(
            AccessInvalidationJobLease lease);

    void checkpoint(
            AccessInvalidationJobLease lease,
            long nextCursor,
            boolean completed,
            Instant updatedAt);

    default void checkpoint(
            AccessInvalidationJobLease lease,
            long nextCursor,
            String nextCursorKey,
            boolean completed,
            Instant updatedAt) {
        throw new IllegalStateException(
                "ACCESS_INVALIDATION_KEYSET_CHECKPOINT_REQUIRED");
    }

    void failed(
            AccessInvalidationJobLease lease,
            String reasonCode,
            Instant failedAt,
            Instant nextAttemptAt,
            boolean quarantine);
}
