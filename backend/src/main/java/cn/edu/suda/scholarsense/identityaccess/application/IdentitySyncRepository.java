package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IdentitySyncRepository extends IdentitySyncRejectionPort {
    Optional<IdentityCheckpoint> checkpoint(CheckpointKey key);

    Optional<String> envelopeDigest(UUID batchId);

    void apply(NormalizedIdentityBatch batch, IdentityLease lease, Instant appliedAt);

    default boolean leaseIsCurrent(IdentityLease lease) {
        return true;
    }

    default List<cn.edu.suda.scholarsense.identityaccess.domain.OrganizationNode>
            currentOrganizations(CheckpointKey key) {
        return List.of();
    }

    default boolean currentAccountExists(CheckpointKey key, UUID accountId) {
        return false;
    }

    default boolean currentOrganizationExists(CheckpointKey key, UUID organizationId) {
        return false;
    }

    default Optional<IdentityRecordState> currentRecord(
            CheckpointKey key,
            IdentityRecordKind kind,
            String externalRefDigest) {
        return Optional.empty();
    }

    default Optional<String> currentSubjectBinding(
            CheckpointKey key, String externalRefDigest) {
        return Optional.empty();
    }

    default Optional<String> currentSubjectBinding(
            CheckpointKey key, UUID accountId) {
        return Optional.empty();
    }

    default List<String> currentSubjectBindingsForOrganization(
            CheckpointKey key, UUID organizationId) {
        return List.of();
    }
}
