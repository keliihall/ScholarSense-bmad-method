package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResponsibilitySyncRepository extends IdentitySyncRejectionPort {
    Optional<IdentityCheckpoint> checkpoint(CheckpointKey key);

    Optional<String> envelopeDigest(UUID batchId);

    Optional<ResponsibilityRecordState> currentRecord(
            CheckpointKey key, String relationRefToken);

    long identityOrgWatermark(String feedId, String partitionId);

    default void recordHeartbeat(
            NormalizedResponsibilityBatch batch,
            IdentityLease lease,
            Instant appliedAt) {}

    void apply(
            NormalizedResponsibilityBatch batch,
            IdentityLease lease,
            Instant appliedAt);

    default void apply(
            NormalizedResponsibilityBatch batch,
            IdentityLease lease,
            Instant appliedAt,
            List<ResponsibilityScopeProjectionUpdate> scopeUpdates) {
        apply(batch, lease, appliedAt);
    }

    default List<ResponsibilityExceptionAuditTransition>
            applyWithAuditTransitions(
                    NormalizedResponsibilityBatch batch,
                    IdentityLease lease,
                    Instant appliedAt,
                    List<ResponsibilityScopeProjectionUpdate> scopeUpdates) {
        apply(batch, lease, appliedAt, scopeUpdates);
        return List.of();
    }

    List<AuthoritativeResponsibilityRelation> currentByStudentDigest(
            CheckpointKey key,
            String studentEquivalenceDigest,
            Instant serverNow);

    default boolean qualityGateTrusted(
            CheckpointKey key, String studentEquivalenceDigest) {
        return true;
    }
}
