package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
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

    /** Exact, trusted current row used by identity-cascade SLO proof. */
    default Optional<AuthoritativeResponsibilityRelation>
            currentCascadeScope(
                    CheckpointKey key,
                    AccessInvalidationLineageId accessLineageId,
                    UUID counselorAccountId,
                    String studentEquivalenceDigest,
                    Instant serverNow) {
        throw new IdentitySyncException(
                "RESPONSIBILITY_SCOPE_TARGETED_READBACK_UNAVAILABLE");
    }

    default boolean qualityGateTrusted(
            CheckpointKey key, String studentEquivalenceDigest) {
        return true;
    }

    /** V2 uses an independent checkpoint so a replay from watermark zero cannot see V1 state. */
    default Optional<IdentityCheckpoint> v2ShadowCheckpoint(CheckpointKey key) {
        throw v2ShadowUnavailable();
    }

    default Optional<String> v2ShadowEnvelopeDigest(UUID batchId) {
        throw v2ShadowUnavailable();
    }

    default Optional<ResponsibilityRecordState> v2ShadowCurrentRecord(
            CheckpointKey key, String relationRefToken) {
        throw v2ShadowUnavailable();
    }

    default Optional<AccessInvalidationLineageId> v2BoundLineage(
            CheckpointKey key, String relationRefToken) {
        throw v2ShadowUnavailable();
    }

    default List<AuthoritativeResponsibilityRelation>
            v2ShadowCurrentByStudentDigest(
                    CheckpointKey key,
                    String studentEquivalenceDigest,
                    Instant serverNow) {
        throw v2ShadowUnavailable();
    }

    default void recordV2ShadowHeartbeat(
            NormalizedResponsibilityBatch batch,
            IdentityLease lease,
            Instant appliedAt) {
        throw v2ShadowUnavailable();
    }

    default void applyV2Shadow(
            NormalizedResponsibilityBatch batch,
            IdentityLease lease,
            Instant appliedAt,
            List<ResponsibilityScopeProjectionUpdate> scopeUpdates) {
        throw v2ShadowUnavailable();
    }

    default boolean v2ShadowActive(CheckpointKey key) {
        throw v2ShadowUnavailable();
    }

    default ResponsibilityV2ReconciliationEvidence
            recordV2Reconciliation(
                    ResponsibilityFullSnapshot snapshot,
                    Instant reconciledAt) {
        throw v2ShadowUnavailable();
    }

    default ResponsibilityV2ProjectionFingerprint v2ShadowFingerprint(
            CheckpointKey key) {
        throw v2ShadowUnavailable();
    }

    default List<AccessInvalidationFact> v2ShadowInvalidationFacts(
            CheckpointKey key) {
        throw v2ShadowUnavailable();
    }

    default List<ResponsibilityV2ExpiryCandidate> v2ShadowExpiryCandidates(
            CheckpointKey key, Instant activatedAt) {
        throw v2ShadowUnavailable();
    }

    default void markV2InvalidationReplayMaterialized(
            ResponsibilityV2CutoverRequest request, int factCount) {
        throw v2ShadowUnavailable();
    }

    default IdentityCheckpoint activateV2Shadow(
            ResponsibilityV2CutoverRequest request, Instant activatedAt) {
        throw v2ShadowUnavailable();
    }

    default Optional<ResponsibilityV2CutoverCommandState>
            v2CutoverCommand(UUID commandId) {
        throw v2ShadowUnavailable();
    }

    default void beginV2CutoverCommand(
            ResponsibilityV2CutoverCommand command) {
        throw v2ShadowUnavailable();
    }

    default void finishV2CutoverCommand(
            UUID commandId,
            String status,
            String reasonCode,
            UUID snapshotId,
            Instant completedAt) {
        throw v2ShadowUnavailable();
    }

    private static IdentitySyncException v2ShadowUnavailable() {
        return new IdentitySyncException(
                "RESPONSIBILITY_V2_SHADOW_UNAVAILABLE");
    }
}
