package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Optional;

/**
 * Required production port owned by the consumer-registry authority.
 *
 * <p>There is deliberately no conformance or empty implementation in production wiring. An empty
 * {@link Optional#empty()} and a runtime failure both mean that no trusted authority row can be
 * ingested for this attempt. The owner still records a non-destructive
 * {@code CONSUMER_REGISTRY_UNAVAILABLE} result using the preallocated evidence id. An absent port
 * is a startup error.
 */
@FunctionalInterface
public interface ConsumerRegistryAuthorityPort {
    Optional<QualitySnapshotRetentionAuthorityEvidence> verify(
            QualitySnapshotRetentionCandidate candidate, java.util.UUID authorityEvidenceId);
}
