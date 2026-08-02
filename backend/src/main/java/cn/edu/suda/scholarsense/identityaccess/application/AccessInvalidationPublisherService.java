package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAggregateType;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationSnapshot;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationState;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationCause;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationChangeKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationDependencyWatermark;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageHead;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReason;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationRetention;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSourceVector;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSubjectSnapshot;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.OrganizationType;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.TargetRole;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Converts committed authority changes into immutable invalidation facts.
 * Calls run inside the caller's core synchronization transaction.
 */
public final class AccessInvalidationPublisherService
        implements AccessInvalidationChangePublisherPort {
    private static final Duration RETENTION = Duration.ofDays(2190);
    private final AccessInvalidationStorePort store;
    private final AccessInvalidationImpactJobPort impactJobs;
    private final AccessInvalidationExpiryPort expiryJobs;
    private final AccessInvalidationEventCodecPort events;
    private final AccessInvalidationIdPort identifiers;
    private final AccessInvalidationAuditPort audit;

    public AccessInvalidationPublisherService(
            AccessInvalidationStorePort store,
            AccessInvalidationImpactJobPort impactJobs,
            AccessInvalidationEventCodecPort events,
            AccessInvalidationIdPort identifiers) {
        this(
                store,
                impactJobs,
                (jobId, lineageId, effectiveTo, traceId) ->
                        cn.edu.suda.scholarsense.identityaccess.domain
                                .AccessInvalidationJob.pending(
                                jobId,
                                cn.edu.suda.scholarsense.identityaccess.domain
                                        .AccessInvalidationJobKind.EXPIRY,
                                lineageId,
                                effectiveTo,
                                traceId),
                events,
                identifiers,
                ignored -> {});
    }

    public AccessInvalidationPublisherService(
            AccessInvalidationStorePort store,
            AccessInvalidationImpactJobPort impactJobs,
            AccessInvalidationExpiryPort expiryJobs,
            AccessInvalidationEventCodecPort events,
            AccessInvalidationIdPort identifiers) {
        this(
                store,
                impactJobs,
                expiryJobs,
                events,
                identifiers,
                ignored -> {});
    }

    public AccessInvalidationPublisherService(
            AccessInvalidationStorePort store,
            AccessInvalidationImpactJobPort impactJobs,
            AccessInvalidationExpiryPort expiryJobs,
            AccessInvalidationEventCodecPort events,
            AccessInvalidationIdPort identifiers,
            AccessInvalidationAuditPort audit) {
        this.store = store;
        this.impactJobs = impactJobs;
        this.expiryJobs = expiryJobs;
        this.events = events;
        this.identifiers = identifiers;
        this.audit = audit;
    }

    @Override
    public void publish(CommittedIdentityChangeSet changeSet) {
        for (IdentitySourceFact sourceFact :
                changeSet.batch().sourceFacts()) {
            identityPublication(changeSet.batch(), sourceFact)
                    .ifPresent(publication -> publishIdentityCause(
                            changeSet, sourceFact, publication));
        }
    }

    @Override
    public void publish(
            CommittedResponsibilityChangeSet changeSet) {
        if (!"RESPONSIBILITY-AUTHORITY-2.0.0".equals(
                changeSet.batch().contractVersion())) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_REPLAY_REQUIRED");
        }
        changeSet.batch().relations().stream()
                .sorted(Comparator.comparing(
                        relation -> relation.lineageId().value()))
                .forEach(relation ->
                        publishResponsibility(changeSet, relation));
    }

    private void publishIdentityCause(
            CommittedIdentityChangeSet changeSet,
            IdentitySourceFact sourceFact,
            IdentityPublication publication) {
        AccessInvalidationLineageId lineage =
                new AccessInvalidationLineageId(
                        "lin_" + sourceFact.externalRefDigest());
        Optional<AccessInvalidationLineageHead> head =
                store.head(lineage.value());
        long version = head.map(
                        AccessInvalidationLineageHead::aggregateVersion)
                .orElse(0L)
                + 1;
        UUID supersedes =
                head.map(AccessInvalidationLineageHead::eventId)
                        .orElse(null);
        String aggregateId =
                "cause_" + sourceFact.externalRefDigest();
        AccessInvalidationFact fact = new AccessInvalidationFact(
                sourceFact.eventId(),
                changeSet.batch().traceId(),
                publication.changeKind(),
                publication.reason(),
                lineage,
                supersedes,
                null,
                AccessInvalidationAggregateType.IDENTITY_CAUSE,
                aggregateId,
                version,
                version,
                changeSet.committedAt(),
                new AccessInvalidationSourceVector(
                        changeSet.batch().key().sourceId(),
                        changeSet.batch().sourceVersion(),
                        changeSet.batch().toWatermark(),
                        List.of(
                                new AccessInvalidationDependencyWatermark(
                                        changeSet.batch().key().feedId(),
                                        changeSet.batch().key().partitionId(),
                                        changeSet.batch().toWatermark()))),
                snapshots(sourceFact.externalRefDigest()),
                identityAuthorization(publication),
                retention(changeSet.committedAt()),
                sourceFact.payloadDigest());
        append(fact, head, changeSet.committedAt());
        AccessInvalidationCause cause = new AccessInvalidationCause(
                fact.eventId(),
                fact.lineageId(),
                fact.reasonCode(),
                fact.sourceVector(),
                fact.effectiveAt(),
                fact.traceId());
        impactJobs.enqueue(
                identifiers.next(changeSet.committedAt()),
                cause,
                fact.retention().retainUntil());
    }

    private void publishResponsibility(
            CommittedResponsibilityChangeSet changeSet,
            AuthoritativeResponsibilityRelation relation) {
        if (!relation.hasInvalidationMetadata()) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_CHANGE_METADATA_REQUIRED");
        }
        AccessInvalidationLineageId lineage = relation.lineageId();
        Optional<AccessInvalidationLineageHead> head =
                store.head(lineage.value());
        UUID expectedSupersedes =
                head.map(AccessInvalidationLineageHead::eventId)
                        .orElse(null);
        if (!java.util.Objects.equals(
                expectedSupersedes, relation.supersedesId())) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_SUPERSEDES_INVALID");
        }
        long version = head.map(
                        AccessInvalidationLineageHead::aggregateVersion)
                .orElse(0L)
                + 1;
        AccessInvalidationFact fact =
                ResponsibilityInvalidationFactFactory.create(
                        changeSet.batch(),
                        changeSet.scopeUpdates(),
                        relation,
                        version);
        append(fact, head, changeSet.committedAt());
        Instant effectiveTo =
                relation.effectiveInterval().effectiveTo();
        if (relation.status() == ResponsibilityStatus.ACTIVE
                && effectiveTo != null) {
            expiryJobs.enqueue(
                    identifiers.next(changeSet.committedAt()),
                    lineage,
                    fact.eventId(),
                    fact.aggregateVersion(),
                    effectiveTo,
                    AccessInvalidationReason.RELATION_EXPIRED,
                    fact.traceId(),
                    changeSet.committedAt());
        }
    }

    private void append(
            AccessInvalidationFact fact,
            Optional<AccessInvalidationLineageHead> head,
            Instant createdAt) {
        long expectedVersion =
                head.map(AccessInvalidationLineageHead::aggregateVersion)
                        .orElse(0L);
        long expectedFence =
                store.fencingToken(fact.lineageId().value());
        String payload = events.encode(fact);
        store.append(new AccessInvalidationAppendCommand(
                fact,
                identifiers.next(createdAt),
                expectedVersion,
                expectedFence,
                payload,
                createdAt));
        audit.factAppended(fact);
    }

    private static Optional<IdentityPublication> identityPublication(
            NormalizedIdentityBatch batch,
            IdentitySourceFact fact) {
        return switch (fact.recordKind()) {
            case ACCOUNT -> batch.accounts().stream()
                    .filter(account -> account.externalRefDigest()
                            .equals(fact.externalRefDigest()))
                    .findFirst()
                    .map(account -> account.status()
                                    == AuthoritativeStatus.INACTIVE
                            ? IdentityPublication.invalidated(
                                    AccessInvalidationReason.ACCOUNT_DISABLED)
                            : IdentityPublication.corrected(
                                    IdentityComponent.ACCOUNT));
            case EMPLOYMENT_ROLE -> batch.roleBindings().stream()
                    .filter(binding -> binding.externalRefDigest()
                            .equals(fact.externalRefDigest()))
                    .filter(binding -> binding.targetRole()
                            == TargetRole.R1_COUNSELOR)
                    .findFirst()
                    .map(binding -> binding.status()
                                    == AuthoritativeStatus.INACTIVE
                            ? IdentityPublication.invalidated(
                                    AccessInvalidationReason
                                            .R1_EMPLOYMENT_INVALID)
                            : IdentityPublication.corrected(
                                    IdentityComponent.R1_EMPLOYMENT));
            case ORGANIZATION -> batch.organizations().stream()
                    .filter(organization -> organization.externalRefDigest()
                            .equals(fact.externalRefDigest()))
                    .filter(organization -> organization.organizationType()
                            == OrganizationType.COLLEGE)
                    .findFirst()
                    .map(organization -> organization.status()
                                    == AuthoritativeStatus.INACTIVE
                            ? IdentityPublication.invalidated(
                                    AccessInvalidationReason.COLLEGE_INVALID)
                            : IdentityPublication.corrected(
                                    IdentityComponent.COLLEGE));
        };
    }

    private static AccessInvalidationSubjectSnapshot snapshots(
            String digest) {
        return new AccessInvalidationSubjectSnapshot(
                "subtok_" + digest,
                "scptok_" + digest,
                digest,
                "ACCESS-INVALIDATION-TOKENIZATION-1.0.0");
    }

    private static AccessInvalidationAuthorizationSnapshot
            identityAuthorization(IdentityPublication publication) {
        boolean corrected = publication.changeKind()
                == AccessInvalidationChangeKind.CORRECTED;
        AccessInvalidationReason reason = publication.reason();
        return new AccessInvalidationAuthorizationSnapshot(
                AccessInvalidationAuthorizationState.INVALIDATED,
                corrected
                        ? publication.component() == IdentityComponent.ACCOUNT
                        : reason
                                != AccessInvalidationReason.ACCOUNT_DISABLED,
                corrected
                        ? publication.component()
                                == IdentityComponent.R1_EMPLOYMENT
                        : reason
                                != AccessInvalidationReason
                                        .R1_EMPLOYMENT_INVALID,
                corrected
                        ? publication.component() == IdentityComponent.COLLEGE
                        : reason != AccessInvalidationReason.COLLEGE_INVALID,
                false,
                "RFP-1.0.0");
    }

    private static AccessInvalidationRetention retention(
            Instant effectiveAt) {
        return new AccessInvalidationRetention(
                "restricted",
                "RS-1.0.0",
                effectiveAt.plus(RETENTION),
                false);
    }

    private enum IdentityComponent {
        ACCOUNT,
        R1_EMPLOYMENT,
        COLLEGE
    }

    private record IdentityPublication(
            AccessInvalidationChangeKind changeKind,
            AccessInvalidationReason reason,
            IdentityComponent component) {
        private static IdentityPublication invalidated(
                AccessInvalidationReason reason) {
            return new IdentityPublication(
                    AccessInvalidationChangeKind.INVALIDATED, reason, null);
        }

        private static IdentityPublication corrected(
                IdentityComponent component) {
            return new IdentityPublication(
                    AccessInvalidationChangeKind.CORRECTED,
                    AccessInvalidationReason.SOURCE_CORRECTION,
                    component);
        }
    }
}
