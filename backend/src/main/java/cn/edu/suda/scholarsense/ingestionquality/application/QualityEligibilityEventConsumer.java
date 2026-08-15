package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyQualityState;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityEvaluator;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityFuseEvaluationProvenance;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityFuseTaskAction;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityFuseTransition;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityFuseTransitionPolicy;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityOverallResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyMember;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyRegistry;
import cn.edu.suda.scholarsense.shared.observability.ObservationPort;
import cn.edu.suda.scholarsense.shared.observability.SafeObservationAttributes;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContextCodec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Consumes the frozen Story 2.3 event without treating technical failures as quality state. */
public final class QualityEligibilityEventConsumer {
    private static final String UPSTREAM_SOURCE = "urn:scholarsense:ingestion-quality";
    private static final String QMDP_VERSION = "QMDP-1.0.0";
    private static final String QG_VERSION = "QG-1.0.0";
    private static final String DEPENDENCY_UNAVAILABLE =
            "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE";

    private final QualityEligibilitySnapshotLookupPort snapshotLookup;
    private final QualityEligibilityEventTransactionPort transaction;
    private final RuleDependencyRegistry registry;
    private final QualityFuseWorkItemKeyPort workItemKeys;
    private final ObservationPort observations;
    private final W3cTraceContextCodec traceCodec;
    private final QualityEligibilityEvaluator evaluator = new QualityEligibilityEvaluator();
    private final QualityFuseTransitionPolicy transitionPolicy =
            new QualityFuseTransitionPolicy();

    public QualityEligibilityEventConsumer(
            QualityEligibilitySnapshotLookupPort snapshotLookup,
            QualityEligibilityEventTransactionPort transaction,
            RuleDependencyRegistry registry,
            QualityFuseWorkItemKeyPort workItemKeys) {
        this(snapshotLookup, transaction, registry, workItemKeys, null, null);
    }

    public QualityEligibilityEventConsumer(
            QualityEligibilitySnapshotLookupPort snapshotLookup,
            QualityEligibilityEventTransactionPort transaction,
            RuleDependencyRegistry registry,
            QualityFuseWorkItemKeyPort workItemKeys,
            ObservationPort observations,
            W3cTraceContextCodec traceCodec) {
        this.snapshotLookup = Objects.requireNonNull(snapshotLookup);
        this.transaction = Objects.requireNonNull(transaction);
        this.registry = Objects.requireNonNull(registry);
        this.workItemKeys = Objects.requireNonNull(workItemKeys);
        this.observations = observations;
        this.traceCodec = traceCodec;
        if ((observations == null) != (traceCodec == null)) {
            throw new IllegalArgumentException("consumer observability must be configured atomically");
        }
    }

    public QualityEligibilityMutation consume(UpstreamQualityEvent event) {
        Objects.requireNonNull(event);
        if (observations == null) {
            return transaction.transact(event, state -> plan(event, state));
        }
        var parent = traceCodec.extract(event.traceparent(), true).context();
        try (ObservationPort.ObservationScope scope = observations.start(
                "event.consume",
                ObservationPort.ObservationKind.CONSUMER,
                SafeObservationAttributes.create()
                        .low("module", "ingestion-quality")
                        .low("operation", "event.consume")
                        .low("outcome", "success"),
                parent)) {
            try {
                QualityEligibilityMutation mutation =
                        transaction.transact(event, state -> plan(event, state));
                scope.outcome(outcome(mutation.outcome()));
                return mutation;
            } catch (RuntimeException failure) {
                scope.outcome(failureOutcome(failure));
                scope.error(failure);
                throw failure;
            }
        }
    }

    private static String outcome(QualityEligibilityProcessingOutcome outcome) {
        return switch (outcome) {
            case DUPLICATE, OLD -> "duplicate";
            case GAP -> "gap";
            case POISONED -> "poison";
            case APPLIED, PENDING_PUBLICATION -> "success";
        };
    }

    private static String failureOutcome(RuntimeException failure) {
        if (failure instanceof IngestionQualityApplicationException application) {
            if ("INGESTION_QUALITY_IDEMPOTENCY_MISMATCH".equals(application.code())) {
                return "conflict";
            }
            if (DEPENDENCY_UNAVAILABLE.equals(application.code())) {
                return "unavailable";
            }
        }
        return "failure";
    }

    private QualityEligibilityMutation plan(
            UpstreamQualityEvent event, QualityEligibilityProcessingState state) {
        QualityEligibilityInboxEntry existing = state.currentInbox();
        if (existing != null) {
            if (!existing.payloadDigest().equals(event.payloadDigest())) {
                throw new IngestionQualityApplicationException(
                        "INGESTION_QUALITY_IDEMPOTENCY_MISMATCH");
            }
            if (existing.outcome() != QualityEligibilityProcessingOutcome.GAP) {
                return QualityEligibilityMutation.noChange(
                        QualityEligibilityProcessingOutcome.DUPLICATE, state);
            }
        }

        UpstreamQualityEventKind kind;
        String dependencyId;
        try {
            kind = kind(event);
            dependencyId = dependencyId(event.sourceId());
        } catch (RuntimeException invalid) {
            return poison(event, state, "INGESTION_QUALITY_UPSTREAM_BINDING_INVALID");
        }
        QualityEligibilitySnapshotEvidence snapshot;
        try {
            snapshot = snapshotLookup.findExact(
                            event.batchId(), event.snapshotId(), event.snapshotImmutableHash())
                    .orElse(null);
        } catch (RuntimeException unavailable) {
            throw new IngestionQualityApplicationException(
                    DEPENDENCY_UNAVAILABLE, unavailable);
        }
        if (snapshot == null) {
            return poison(event, state, "INGESTION_QUALITY_UPSTREAM_BINDING_INVALID");
        }
        try {
            verifySnapshot(event, snapshot);
        } catch (RuntimeException invalid) {
            return poison(event, state, "INGESTION_QUALITY_UPSTREAM_BINDING_INVALID");
        }

        QualityEligibilityCursor current = state.cursor();
        if (current == null && event.sourceVersion() != 1) {
            return gap(event, state, null, dependencyId, 1);
        }
        if (current != null) {
            if (!current.sourceId().equals(event.sourceId())
                    || !current.dependencyId().equals(dependencyId)) {
                return poison(event, state, "INGESTION_QUALITY_UPSTREAM_BINDING_INVALID");
            }
            if (event.sourceVersion() < current.sourceVersion()) {
                return QualityEligibilityMutation.noChange(
                        QualityEligibilityProcessingOutcome.OLD, state);
            }
            if (event.sourceVersion() > current.sourceVersion() + 1) {
                return gap(event, state, current, dependencyId, current.sourceVersion() + 1);
            }
            if (event.sourceVersion() == current.sourceVersion()) {
                if (kind == UpstreamQualityEventKind.PUBLISHED
                        && current.stage() == QualityEligibilityCursorStage.PENDING_PUBLICATION
                        && state.pendingPair() != null
                        && state.pendingPair().matches(event)) {
                    return applyTerminal(event, state, snapshot, dependencyId,
                            QualityEligibilityStatus.ELIGIBLE,
                            current.lineageRevision(), current.aggregateVersion() + 1);
                }
                boolean directCorrection = kind != UpstreamQualityEventKind.PUBLISHED
                        && event.supersedesBatchId() != null
                        && event.supersedesBatchId().equals(current.batchId())
                        && event.lineageId().equals(current.lineageId());
                if (!directCorrection) {
                    return QualityEligibilityMutation.noChange(
                            QualityEligibilityProcessingOutcome.OLD, state);
                }
                return applyAssessed(event, state, snapshot, dependencyId, kind,
                        current.lineageRevision() + 1, current.aggregateVersion() + 1);
            }
        }
        if (kind == UpstreamQualityEventKind.PUBLISHED) {
            return poison(event, state, "INGESTION_QUALITY_UPSTREAM_BINDING_INVALID");
        }
        long lineageRevision = 0;
        long aggregateVersion = current == null ? 1 : current.aggregateVersion() + 1;
        return applyAssessed(
                event, state, snapshot, dependencyId, kind, lineageRevision, aggregateVersion);
    }

    private QualityEligibilityMutation applyAssessed(
            UpstreamQualityEvent event,
            QualityEligibilityProcessingState state,
            QualityEligibilitySnapshotEvidence snapshot,
            String dependencyId,
            UpstreamQualityEventKind kind,
            long lineageRevision,
            long aggregateVersion) {
        if (kind == UpstreamQualityEventKind.ASSESSED_PASSED) {
            QualityEligibilityCursor cursor = cursor(
                    event, dependencyId, lineageRevision,
                    QualityEligibilityCursorStage.PENDING_PUBLICATION, aggregateVersion);
            PendingQualityPair pending = new PendingQualityPair(
                    event.eventId(), event.batchId(), event.snapshotId(),
                    event.snapshotImmutableHash(), event.sourceVersion(), event.lineageId());
            return new QualityEligibilityMutation(
                    QualityEligibilityProcessingOutcome.PENDING_PUBLICATION,
                    cursor, pending, null, List.of(), null, null, snapshot);
        }
        return applyTerminal(
                event, state, snapshot, dependencyId, QualityEligibilityStatus.FUSED,
                lineageRevision, aggregateVersion);
    }

    private QualityEligibilityMutation applyTerminal(
            UpstreamQualityEvent event,
            QualityEligibilityProcessingState state,
            QualityEligibilitySnapshotEvidence snapshot,
            String dependencyId,
            QualityEligibilityStatus status,
            long lineageRevision,
            long aggregateVersion) {
        QualityEligibilityCursor cursor = cursor(
                event, dependencyId, lineageRevision,
                QualityEligibilityCursorStage.TERMINAL, aggregateVersion);
        DependencyQualityState dependency = new DependencyQualityState(
                event.sourceId(), event.sourceVersion(), event.lineageId(), lineageRevision,
                dependencyId, event.sourceVersion(), status, true, event.watermark());
        ArrayList<DependencyQualityState> states = new ArrayList<>(state.dependencyStates());
        states.removeIf(value -> value.dependencyId().equals(dependencyId));
        states.add(dependency);
        List<RuleEligibilityDecision> decisions = registry.rules().stream()
                .filter(rule -> rule.members().stream().map(RuleDependencyMember::dependencyId)
                        .anyMatch(dependencyId::equals))
                .map(rule -> applyLatch(state, rule, evaluator.evaluate(rule, states.stream()
                                .filter(stateValue -> rule.members().stream()
                                        .anyMatch(member -> member.dependencyId().equals(
                                                stateValue.dependencyId())))
                                .toList()), status == QualityEligibilityStatus.FUSED,
                        event.sourceId(), dependencyId))
                .toList();
        QualityFuseTaskPlan fuseTaskPlan = planTask(
                event, snapshot, dependency, decisions, state);
        RecoveryObservationProgressState observationProgress = planObservationProgress(
                event, state, dependencyId, status, lineageRevision);
        return new QualityEligibilityMutation(
                QualityEligibilityProcessingOutcome.APPLIED, cursor, null, dependency,
                decisions, null, null, snapshot, fuseTaskPlan, observationProgress);
    }

    private RecoveryObservationProgressState planObservationProgress(
            UpstreamQualityEvent event,
            QualityEligibilityProcessingState state,
            String dependencyId,
            QualityEligibilityStatus evaluatedStatus,
            long lineageRevision) {
        String key = QualityEligibilityProcessingState.episodeKey(
                event.sourceId(), dependencyId);
        RecoveryObservationProgressState progress = state.recoveryObservations().get(key);
        if (progress == null) return null;
        if (progress.status() != RecoveryObservationProgressStatus.OBSERVING
                && progress.status() != RecoveryObservationProgressStatus.READY) {
            return null;
        }
        QualityFuseEpisodeState episode = state.activeEpisodes().get(key);
        if (episode == null || episode.generation() != progress.generation()) {
            throw new IllegalArgumentException("RECOVERY_OBSERVATION_PROGRESS_FENCE_INVALID");
        }
        if (evaluatedStatus == QualityEligibilityStatus.FUSED) {
            return progress.relapsed(
                    event.sourceVersion(), lineageRevision,
                    event.watermark(), event.occurredAt());
        }
        return progress.passed(
                event.sourceVersion(), lineageRevision,
                event.watermark(), event.occurredAt());
    }

    private RuleEligibilityDecision applyLatch(
            QualityEligibilityProcessingState state,
            RuleDependencyDefinition rule,
            cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityDecision evaluated,
            boolean verifiedBusinessFailure,
            String sourceId,
            String dependencyId) {
        String key = rule.ruleVersion().businessKey(registry.registryVersion());
        QualityEligibilityCurrentState current = state.currentEligibilities().get(key);
        QualityFuseEpisodeState episode = state.activeEpisodes().get(
                QualityEligibilityProcessingState.episodeKey(sourceId, dependencyId));
        QualityEligibilityStatus prior = current == null
                ? QualityEligibilityStatus.MISSING : current.status();
        QualityFuseTransition transition = transitionPolicy.apply(
                QualityFuseEvaluationProvenance.REALTIME_ASSESSMENT,
                current != null, prior, evaluated,
                verifiedBusinessFailure && evaluated.status() == QualityEligibilityStatus.FUSED,
                episode != null);
        return RuleEligibilityDecision.applied(rule, transition);
    }

    private QualityFuseTaskPlan planTask(
            UpstreamQualityEvent event,
            QualityEligibilitySnapshotEvidence snapshot,
            DependencyQualityState dependency,
            List<RuleEligibilityDecision> decisions,
            QualityEligibilityProcessingState state) {
        QualityFuseTaskAction action = decisions.stream()
                .map(RuleEligibilityDecision::transition)
                .filter(Objects::nonNull)
                .map(QualityFuseTransition::taskAction)
                .max(Comparator.comparingInt(QualityEligibilityEventConsumer::priority))
                .orElse(QualityFuseTaskAction.NONE);
        if (action == QualityFuseTaskAction.NONE) return null;

        String episodeKey = QualityEligibilityProcessingState.episodeKey(
                event.sourceId(), dependency.dependencyId());
        QualityFuseEpisodeState existing = state.activeEpisodes().get(episodeKey);
        long generation;
        long expectedAggregateVersion;
        UUID episodeId;
        UUID taskId;
        String workItemKey;
        String workItemKeyVersion;
        if (action == QualityFuseTaskAction.CREATE) {
            generation = state.latestEpisodeGenerations().getOrDefault(episodeKey, 0L) + 1;
            expectedAggregateVersion = 0;
            episodeId = QualityFuseIdentifiers.derive(
                    event.eventId(), "quality-fuse-episode:" + episodeKey + ":" + generation);
            taskId = QualityFuseIdentifiers.derive(
                    event.eventId(), "quality-recovery-task:" + episodeKey + ":" + generation);
            QualityFuseWorkItemIdentity identity = workItemKeys.current(
                    event.sourceId(), dependency.dependencyId(), generation);
            workItemKey = identity.workItemKey();
            workItemKeyVersion = identity.keyVersion();
        } else {
            if (existing == null) {
                throw new IllegalArgumentException("INGESTION_QUALITY_FUSE_EPISODE_MISSING");
            }
            generation = existing.generation();
            expectedAggregateVersion = existing.aggregateVersion();
            episodeId = existing.episodeId();
            taskId = existing.recoveryTaskId();
            workItemKey = existing.workItemKey();
            workItemKeyVersion = existing.workItemKeyVersion();
        }
        List<cn.edu.suda.scholarsense.ingestionquality.domain.RuleVersionIdentity> rules =
                decisions.stream().map(decision -> decision.rule().ruleVersion()).toList();
        ArrayList<DependencyQualityState> evidenceStates = new ArrayList<>(
                state.dependencyStates());
        evidenceStates.removeIf(value -> value.dependencyId().equals(dependency.dependencyId()));
        evidenceStates.add(dependency);
        Map<String, DependencyQualityState> statesByDependency = evidenceStates.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        DependencyQualityState::dependencyId, value -> value));
        List<QualityFuseEligibilityEvidence> eligibilityEvidence = decisions.stream()
                .map(decision -> eligibilityEvidence(
                        event, decision, state, statesByDependency))
                .toList();
        String businessKey = event.batchId() + ":" + dependency.dependencyVersion()
                + ":" + episodeKey + ":" + generation;
        return new QualityFuseTaskPlan(
                action, episodeId, taskId, workItemKey, workItemKeyVersion, generation,
                expectedAggregateVersion, event.sourceId(), event.sourceVersion(),
                dependency.dependencyId(),
                dependency.dependencyVersion(), event.batchId(), snapshot.snapshotId(),
                snapshot.immutableHash(), snapshot.qualityGateVersion(),
                snapshot.qualityGateDigest(), snapshot.qmdpVersion(), snapshot.qmdpDigest(),
                snapshot.qshmVersion(), snapshot.qshmDigest(), event.lineageId(),
                event.watermark(), rules, eligibilityEvidence, snapshot.formulaEvidence(),
                businessKey, event.payloadDigest(), event.effectiveAt(), event.occurredAt());
    }

    private QualityFuseEligibilityEvidence eligibilityEvidence(
            UpstreamQualityEvent event,
            RuleEligibilityDecision decision,
            QualityEligibilityProcessingState state,
            Map<String, DependencyQualityState> statesByDependency) {
        String businessKey = decision.rule().ruleVersion().businessKey(
                registry.registryVersion());
        QualityEligibilityCurrentState prior = state.currentEligibilities().get(businessKey);
        UUID eligibilityId = prior == null
                ? QualityFuseIdentifiers.derive(
                        event.eventId(), "eligibility:" + decision.rule().ruleVersion().ruleId())
                : prior.eligibilityId();
        long aggregateVersion = prior == null ? 1 : prior.aggregateVersion() + 1;
        List<QualityFuseMemberEvidence> members = decision.rule().members().stream()
                .map(member -> QualityFuseMemberEvidence.from(
                        member, statesByDependency.get(member.dependencyId()),
                        decision.evaluatedDecision().failedMembers().contains(
                                member.dependencyId())))
                .toList();
        return new QualityFuseEligibilityEvidence(
                eligibilityId, businessKey, aggregateVersion,
                decision.rule().ruleVersion(),
                QualityFuseEligibilityEvidence.digestMembers(members),
                registry.registryVersion(), registry.registryDigest(),
                registry.catalogVersion(), registry.catalogDigest(),
                registry.ruleCatalogVersion(), registry.ruleCatalogDigest(),
                decision.rule().operator(), decision.rule().threshold(),
                decision.transition().priorState(), decision.evaluatedDecision().status(),
                decision.decision().status(), decision.decision().reason(), members);
    }

    private static int priority(QualityFuseTaskAction action) {
        return switch (action) {
            case NONE -> 0;
            case PRESERVE -> 1;
            case UPDATE -> 2;
            case CREATE -> 3;
        };
    }

    private QualityEligibilityMutation gap(
            UpstreamQualityEvent event,
            QualityEligibilityProcessingState state,
            QualityEligibilityCursor current,
            String dependencyId,
            long expected) {
        QualityEligibilityCursor paused = current == null ? null : current.pause();
        QualityEligibilityBackfillRequest backfill = new QualityEligibilityBackfillRequest(
                event.sourceId(), expected, event.sourceVersion(),
                current == null ? 0 : current.aggregateVersion());
        return new QualityEligibilityMutation(
                QualityEligibilityProcessingOutcome.GAP, paused, state.pendingPair(), null,
                List.of(), backfill, null, null);
    }

    private QualityEligibilityMutation poison(
            UpstreamQualityEvent event,
            QualityEligibilityProcessingState state,
            String reasonCode) {
        return new QualityEligibilityMutation(
                QualityEligibilityProcessingOutcome.POISONED, state.cursor(),
                state.pendingPair(), null, List.of(), null,
                new QualityEligibilityQuarantine(
                        event.eventId(), event.sourceId(), reasonCode, event.payloadDigest()),
                null);
    }

    private static QualityEligibilityCursor cursor(
            UpstreamQualityEvent event,
            String dependencyId,
            long lineageRevision,
            QualityEligibilityCursorStage stage,
            long aggregateVersion) {
        return new QualityEligibilityCursor(
                event.sourceId(), dependencyId, event.sourceVersion(), event.lineageId(),
                lineageRevision, event.batchId(), stage, false, aggregateVersion);
    }

    private String dependencyId(String sourceId) {
        List<String> dependencies = registry.rules().stream()
                .flatMap(rule -> rule.members().stream())
                .filter(member -> member.sourceId().equals(sourceId))
                .map(RuleDependencyMember::dependencyId).distinct().toList();
        if (dependencies.size() != 1) throw invalidBinding();
        return dependencies.getFirst();
    }

    private static UpstreamQualityEventKind kind(UpstreamQualityEvent event) {
        if (!UPSTREAM_SOURCE.equals(event.eventSource())
                || event.snapshotAggregateVersion() != 3
                || !QMDP_VERSION.equals(event.qmdpVersion())
                || !QG_VERSION.equals(event.qualityGateVersion())) {
            throw invalidBinding();
        }
        if (UpstreamQualityEventKind.PUBLISHED.matches(
                event.eventType(), event.schemaVersion())) {
            if (event.batchAggregateVersion() != 4
                    || event.batchStatus() != DataBatchStatus.PUBLISHED
                    || event.snapshotResult() != QualityOverallResult.QUALITY_PASSED) {
                throw invalidBinding();
            }
            return UpstreamQualityEventKind.PUBLISHED;
        }
        if (!UpstreamQualityEventKind.ASSESSED_FAILED.matches(
                    event.eventType(), event.schemaVersion())
                || event.batchAggregateVersion() != 3) {
            throw invalidBinding();
        }
        if (event.batchStatus() == DataBatchStatus.QUALITY_FAILED
                && event.snapshotResult() == QualityOverallResult.QUALITY_FAILED) {
            return UpstreamQualityEventKind.ASSESSED_FAILED;
        }
        if (event.batchStatus() == DataBatchStatus.QUALITY_PASSED
                && event.snapshotResult() == QualityOverallResult.QUALITY_PASSED) {
            return UpstreamQualityEventKind.ASSESSED_PASSED;
        }
        throw invalidBinding();
    }

    private static void verifySnapshot(
            UpstreamQualityEvent event, QualityEligibilitySnapshotEvidence snapshot) {
        if (!snapshot.snapshotId().equals(event.snapshotId())
                || !snapshot.batchId().equals(event.batchId())
                || !snapshot.sourceId().equals(event.sourceId())
                || snapshot.result() != event.snapshotResult()
                || !snapshot.observationWindow().equals(event.observationWindow())
                || !snapshot.cutoffAt().equals(event.cutoffAt())
                || !snapshot.watermark().equals(event.watermark())
                || !snapshot.manifestDigest().equals(event.manifestDigest())
                || !snapshot.sourceSchemaVersion().equals(event.sourceSchemaVersion())
                || !snapshot.sourceSchemaDigest().equals(event.sourceSchemaDigest())
                || !snapshot.qmdpVersion().equals(event.qmdpVersion())
                || !snapshot.qmdpDigest().equals(event.qmdpDigest())
                || !snapshot.qualityGateVersion().equals(event.qualityGateVersion())
                || !snapshot.qualityGateDigest().equals(event.qualityGateDigest())
                || !snapshot.lineageId().equals(event.lineageId())
                || !snapshot.effectiveAt().equals(event.snapshotEffectiveAt())
                || !snapshot.immutableHash().equals(event.snapshotImmutableHash())) {
            throw invalidBinding();
        }
    }

    private static IllegalArgumentException invalidBinding() {
        return new IllegalArgumentException(
                "INGESTION_QUALITY_UPSTREAM_BINDING_INVALID");
    }
}
