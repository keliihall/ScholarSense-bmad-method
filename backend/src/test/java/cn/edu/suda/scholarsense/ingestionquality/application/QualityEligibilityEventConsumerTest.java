package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ingestionquality.domain.BatchObservationWindow;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatchStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyQualityState;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyRequirement;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityReason;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityFuseTaskAction;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityOverallResult;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyMember;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyRegistry;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleVersionIdentity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class QualityEligibilityEventConsumerTest {

    @Test
    void failedAssessedV3IsTerminalAndImmediatelyFusesDependency() {
        Fixture fixture = new Fixture();
        QualityEligibilityMutation result = fixture.consumer.consume(
                fixture.event(1, UpstreamQualityEventKind.ASSESSED_FAILED));

        assertEquals(QualityEligibilityProcessingOutcome.APPLIED, result.outcome());
        assertEquals(QualityEligibilityStatus.FUSED, result.dependencyState().status());
        assertEquals(QualityEligibilityCursorStage.TERMINAL, result.cursor().stage());
        assertNull(result.pendingPair());
        assertFalse(result.decisions().isEmpty());
        assertEquals(QualityFuseTaskAction.CREATE, result.fuseTaskPlan().action());
    }

    @Test
    void passedV3RemainsPendingUntilExactMatchingPublishedV4() {
        Fixture fixture = new Fixture();
        UpstreamQualityEvent assessed = fixture.event(
                1, UpstreamQualityEventKind.ASSESSED_PASSED);
        QualityEligibilityMutation pending = fixture.consumer.consume(assessed);

        assertEquals(QualityEligibilityProcessingOutcome.PENDING_PUBLICATION,
                pending.outcome());
        assertEquals(QualityEligibilityCursorStage.PENDING_PUBLICATION,
                pending.cursor().stage());
        assertNull(pending.dependencyState());

        QualityEligibilityMutation published = fixture.consumer.consume(
                fixture.publishedFor(assessed));
        assertEquals(QualityEligibilityProcessingOutcome.APPLIED, published.outcome());
        assertEquals(QualityEligibilityStatus.ELIGIBLE,
                published.dependencyState().status());
        assertEquals(QualityEligibilityCursorStage.TERMINAL, published.cursor().stage());
        assertNull(published.pendingPair());
    }

    @Test
    void exactSnapshotBindingFailureIsPoisonAndCannotAdvanceCursorOrBusinessState() {
        Fixture fixture = new Fixture();
        UpstreamQualityEvent event = fixture.event(
                1, UpstreamQualityEventKind.ASSESSED_FAILED);
        fixture.snapshot = fixture.snapshot.withManifestDigest("sha256:" + "9".repeat(64));

        QualityEligibilityMutation poisoned = fixture.consumer.consume(event);

        assertEquals(QualityEligibilityProcessingOutcome.POISONED, poisoned.outcome());
        assertNull(poisoned.cursor());
        assertNull(poisoned.dependencyState());
        assertTrue(poisoned.decisions().isEmpty());
        assertEquals("INGESTION_QUALITY_UPSTREAM_BINDING_INVALID",
                poisoned.quarantine().reasonCode());
    }

    @Test
    void transientSnapshotLookupFailureIsRetryableAndDoesNotPersistPoisonOrDuplicate() {
        Fixture fixture = new Fixture();
        UpstreamQualityEvent event = fixture.event(
                1, UpstreamQualityEventKind.ASSESSED_FAILED);
        fixture.snapshotLookupFailure = new IllegalStateException("temporary database outage");

        IngestionQualityApplicationException failure = assertThrows(
                IngestionQualityApplicationException.class,
                () -> fixture.consumer.consume(event));

        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", failure.code());
        fixture.snapshotLookupFailure = null;
        assertEquals(QualityEligibilityProcessingOutcome.APPLIED,
                fixture.consumer.consume(event).outcome(),
                "the same event must remain retryable rather than becoming poison/duplicate");
    }

    @Test
    void duplicateOldGapAndBackfillAreDeterministicAcrossBatchVersionReset() {
        Fixture fixture = new Fixture();
        UpstreamQualityEvent first = fixture.event(
                1, UpstreamQualityEventKind.ASSESSED_FAILED);
        assertEquals(QualityEligibilityProcessingOutcome.APPLIED,
                fixture.consumer.consume(first).outcome());
        assertEquals(QualityEligibilityProcessingOutcome.DUPLICATE,
                fixture.consumer.consume(first).outcome());

        UpstreamQualityEvent gap = fixture.event(
                3, UpstreamQualityEventKind.ASSESSED_FAILED);
        QualityEligibilityMutation gapResult = fixture.consumer.consume(gap);
        assertEquals(QualityEligibilityProcessingOutcome.GAP, gapResult.outcome());
        assertEquals(2, gapResult.backfillRequest().expectedSourceVersion());
        assertEquals(3, gapResult.backfillRequest().actualSourceVersion());
        assertEquals(1, gapResult.cursor().sourceVersion());
        assertTrue(gapResult.cursor().paused());

        assertEquals(QualityEligibilityProcessingOutcome.APPLIED,
                fixture.consumer.consume(fixture.event(
                        2, UpstreamQualityEventKind.ASSESSED_FAILED)).outcome());
        fixture.snapshot = fixture.snapshotFor(gap);
        assertEquals(QualityEligibilityProcessingOutcome.APPLIED,
                fixture.consumer.consume(gap).outcome());

        UpstreamQualityEvent old = fixture.event(
                1, UpstreamQualityEventKind.ASSESSED_FAILED);
        assertEquals(QualityEligibilityProcessingOutcome.OLD,
                fixture.consumer.consume(old.withEventId(
                        uuid("019d2c7d-4000-7000-8000-000000000499"))).outcome());
    }

    @Test
    void correctionUsesExactSupersedesChainAndNeverOccurredAtOrWatermarkOrdering() {
        Fixture fixture = new Fixture();
        UpstreamQualityEvent first = fixture.event(
                1, UpstreamQualityEventKind.ASSESSED_FAILED);
        fixture.consumer.consume(first);
        UpstreamQualityEvent correction = fixture.event(
                1, UpstreamQualityEventKind.ASSESSED_FAILED)
                .withEventId(uuid("019d2c7d-4000-7000-8000-000000000498"))
                .withBatchId(uuid("019d2c7d-4000-7000-8000-000000000497"))
                .withSupersedesBatchId(first.batchId())
                .withOccurredAt(Instant.parse("2020-01-01T00:00:00Z"))
                .withWatermark("opaque-z-before-or-after-has-no-order");
        fixture.snapshot = fixture.snapshotFor(correction);

        QualityEligibilityMutation result = fixture.consumer.consume(correction);

        assertEquals(QualityEligibilityProcessingOutcome.APPLIED, result.outcome());
        assertEquals(1, result.cursor().sourceVersion());
        assertEquals(1, result.cursor().lineageRevision());
        assertEquals(correction.batchId(), result.cursor().batchId());
    }

    @Test
    void filtersGlobalDependencyStatesToEachAffectedRuleBeforeEvaluation() {
        Fixture fixture = new Fixture();
        assertEquals(QualityEligibilityProcessingOutcome.APPLIED,
                fixture.consumer.consume(fixture.eventForSource(
                        1, UpstreamQualityEventKind.ASSESSED_FAILED,
                        "SRC-P0-CAMPUS-ACCESS-001")).outcome());

        QualityEligibilityMutation secondRule = fixture.consumer.consume(
                fixture.eventForSource(
                        1, UpstreamQualityEventKind.ASSESSED_FAILED,
                        "SRC-P0-CARD-001"));

        assertEquals(QualityEligibilityProcessingOutcome.APPLIED, secondRule.outcome());
        assertEquals(List.of("ECON-012"), secondRule.decisions().stream()
                .map(decision -> decision.rule().ruleVersion().ruleId())
                .toList());
    }

    @Test
    void verifiedFailureAfterEligibleCreatesOneEpisodeTaskAndDuplicateReusesIt() {
        Fixture fixture = new Fixture();
        fixture.transaction.seedEligibility(
                "ACC-SAFE-001", QualityEligibilityStatus.ELIGIBLE,
                QualityEligibilityReason.ALL_REQUIRED_ELIGIBLE);
        UpstreamQualityEvent event = fixture.event(
                1, UpstreamQualityEventKind.ASSESSED_FAILED);

        QualityEligibilityMutation first = fixture.consumer.consume(event);
        QualityEligibilityMutation replay = fixture.consumer.consume(event);

        assertEquals(QualityFuseTaskAction.CREATE, first.fuseTaskPlan().action());
        assertEquals("SRC-P0-CAMPUS-ACCESS-001", first.fuseTaskPlan().sourceId());
        assertEquals("DEP-P0-CAMPUS-ACCESS-001", first.fuseTaskPlan().dependencyId());
        assertEquals(1, first.fuseTaskPlan().episodeGeneration());
        assertEquals("k7", first.fuseTaskPlan().workItemKeyVersion());
        QualityFuseEligibilityEvidence handoff =
                first.fuseTaskPlan().eligibilityEvidence().getFirst();
        assertEquals("ACC-SAFE-001@1.0.0@RULE-DEPENDENCY-REGISTRY-1.0.0",
                handoff.businessKey());
        assertEquals("RULE-DEPENDENCY-REGISTRY-1.0.0", handoff.registryVersion());
        assertEquals("DCC-1.1.0", handoff.dccVersion());
        assertEquals("RC-1.0.0", handoff.ruleCatalogVersion());
        assertEquals(7, handoff.members().size());
        QualityFuseMemberEvidence triggeringMember = handoff.members().stream()
                .filter(member -> member.sourceId().equals("SRC-P0-CAMPUS-ACCESS-001"))
                .findFirst().orElseThrow();
        assertEquals("SOURCE-1.0.0", triggeringMember.sourceContractVersion());
        assertTrue(triggeringMember.failed());
        assertEquals(Boolean.FALSE,
                first.fuseTaskPlan().formulaEvidence().getFirst().comparisonResult());
        assertEquals(QualityEligibilityStatus.FUSED,
                first.decisions().getFirst().decision().status());
        assertEquals(QualityEligibilityProcessingOutcome.DUPLICATE, replay.outcome());
        assertEquals(first.fuseTaskPlan().episodeId(), replay.fuseTaskPlan().episodeId());
        assertEquals(first.fuseTaskPlan().recoveryTaskId(),
                replay.fuseTaskPlan().recoveryTaskId());
        assertEquals(first.fuseTaskPlan().workItemKey(), replay.fuseTaskPlan().workItemKey());
    }

    @Test
    void passedEvidenceCannotAutomaticallyUnlockCurrentFusedEligibility() {
        Fixture fixture = new Fixture();
        fixture.transaction.seedEligibility(
                "ACC-SAFE-001", QualityEligibilityStatus.FUSED,
                QualityEligibilityReason.REQUIRED_MEMBER_FUSED);
        fixture.transaction.seedEpisode(
                "SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001");
        UpstreamQualityEvent assessed = fixture.event(
                1, UpstreamQualityEventKind.ASSESSED_PASSED);
        fixture.consumer.consume(assessed);

        QualityEligibilityMutation published = fixture.consumer.consume(
                fixture.publishedFor(assessed));

        assertEquals(QualityEligibilityStatus.FUSED,
                published.decisions().getFirst().decision().status());
        assertEquals(QualityEligibilityReason.FUSE_LATCHED,
                published.decisions().getFirst().decision().reason());
        assertEquals(QualityFuseTaskAction.PRESERVE,
                published.fuseTaskPlan().action());
        assertEquals("k3", published.fuseTaskPlan().workItemKeyVersion(),
                "an active episode must retain the key version captured at creation");
    }

    @Test
    void recoveringObservationProgressAndRelapseShareTheEventOwnerTransaction() {
        Fixture fixture = new Fixture();
        fixture.transaction.seedEligibility(
                "ACC-SAFE-001", QualityEligibilityStatus.RECOVERING,
                QualityEligibilityReason.RECOVERY_COMMAND_ACCEPTED);
        fixture.transaction.seedEpisode(
                "SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001");
        fixture.transaction.seedObservation(
                "SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001");
        UpstreamQualityEvent assessed = fixture.event(
                1, UpstreamQualityEventKind.ASSESSED_PASSED);
        fixture.consumer.consume(assessed);

        QualityEligibilityMutation published = fixture.consumer.consume(
                fixture.publishedFor(assessed));
        UpstreamQualityEvent failure = fixture.event(
                2, UpstreamQualityEventKind.ASSESSED_FAILED)
                .withOccurredAt(Instant.parse("2026-08-10T00:03:00Z"));
        fixture.snapshot = fixture.snapshotFor(failure);
        QualityEligibilityMutation relapsed = fixture.consumer.consume(failure);

        assertEquals(1,
                published.recoveryObservationProgress().consecutivePassedBatches());
        assertEquals(RecoveryObservationProgressStatus.OBSERVING,
                published.recoveryObservationProgress().status());
        assertEquals(RecoveryObservationProgressStatus.RELAPSED,
                relapsed.recoveryObservationProgress().status());
        assertEquals(QualityEligibilityReason.RECOVERY_RELAPSED,
                relapsed.decisions().getFirst().decision().reason());
    }

    @Test
    void terminalRelapsedObservationDoesNotBlockLaterQualityEvents() {
        Fixture fixture = new Fixture();
        fixture.transaction.seedEligibility(
                "ACC-SAFE-001", QualityEligibilityStatus.FUSED,
                QualityEligibilityReason.RECOVERY_RELAPSED);
        fixture.transaction.seedEpisode(
                "SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001");
        RecoveryObservationProgressState relapsed = RecoveryObservationProgressState.start(
                        uuid("019d2c7d-4000-7000-8006-000000000401"), 1,
                        "SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001",
                        Instant.parse("2026-08-10T00:00:00Z"))
                .relapsed(1, 0, "wm-1", Instant.parse("2026-08-10T00:01:00Z"));
        fixture.transaction.recoveryObservations.put(relapsed.key(), relapsed);

        QualityEligibilityMutation mutation = fixture.consumer.consume(
                fixture.event(1, UpstreamQualityEventKind.ASSESSED_FAILED)
                        .withOccurredAt(Instant.parse("2026-08-10T00:03:00Z")));

        assertEquals(QualityEligibilityProcessingOutcome.APPLIED, mutation.outcome());
        assertEquals(null, mutation.recoveryObservationProgress());
    }

    @Test
    void verifiedFailureAfterTerminalCloseCreatesANewGenerationInsteadOfReopening() {
        Fixture fixture = new Fixture();
        fixture.transaction.seedEligibility(
                "ACC-SAFE-001", QualityEligibilityStatus.ELIGIBLE,
                QualityEligibilityReason.RECOVERY_FINALIZED);
        String episodeKey = QualityEligibilityProcessingState.episodeKey(
                "SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001");
        fixture.transaction.latestGenerations.put(episodeKey, 1L);

        QualityEligibilityMutation mutation = fixture.consumer.consume(
                fixture.event(1, UpstreamQualityEventKind.ASSESSED_FAILED)
                        .withOccurredAt(Instant.parse("2026-08-10T00:03:00Z")));

        assertEquals(QualityFuseTaskAction.CREATE, mutation.fuseTaskPlan().action());
        assertEquals(2, mutation.fuseTaskPlan().episodeGeneration());
        assertEquals(QualityEligibilityStatus.FUSED,
                mutation.decisions().getFirst().decision().status());
        assertEquals(2,
                fixture.transaction.activeEpisodes.get(episodeKey).generation(),
                "the active projection must point only at the successor generation");
    }

    @Test
    void sameEventIdentityWithDifferentBodyDigestIsStableConflict() {
        Fixture fixture = new Fixture();
        UpstreamQualityEvent event = fixture.event(
                1, UpstreamQualityEventKind.ASSESSED_FAILED);
        fixture.consumer.consume(event);

        IngestionQualityApplicationException failure = assertThrows(
                IngestionQualityApplicationException.class,
                () -> fixture.consumer.consume(event.withPayloadDigest(
                        "sha256:" + "f".repeat(64))));

        assertEquals("INGESTION_QUALITY_IDEMPOTENCY_MISMATCH", failure.code());
    }

    private static final class Fixture {
        private final InMemoryTransaction transaction = new InMemoryTransaction();
        private QualityEligibilitySnapshotEvidence snapshot;
        private RuntimeException snapshotLookupFailure;
        private final QualityEligibilityEventConsumer consumer;

        private Fixture() {
            UpstreamQualityEvent seed = event(1, UpstreamQualityEventKind.ASSESSED_FAILED);
            snapshot = snapshotFor(seed);
            consumer = new QualityEligibilityEventConsumer(
                    this::findSnapshot, transaction, registry(),
                    (sourceId, dependencyId, generation) -> new QualityFuseWorkItemIdentity(
                            "qf:" + "7".repeat(64), "k7"));
        }

        private Optional<QualityEligibilitySnapshotEvidence> findSnapshot(
                UUID batchId, UUID snapshotId, String immutableHash) {
            if (snapshotLookupFailure != null) throw snapshotLookupFailure;
            if (snapshot.batchId().equals(batchId)
                    && snapshot.snapshotId().equals(snapshotId)
                    && snapshot.immutableHash().equals(immutableHash)) {
                return Optional.of(snapshot);
            }
            return Optional.empty();
        }

        private UpstreamQualityEvent event(
                long sourceVersion, UpstreamQualityEventKind kind) {
            return eventForSource(
                    sourceVersion, kind, "SRC-P0-CAMPUS-ACCESS-001");
        }

        private UpstreamQualityEvent eventForSource(
                long sourceVersion, UpstreamQualityEventKind kind, String sourceId) {
            String suffix = String.format("%012d", 400 + sourceVersion);
            UUID batchId = uuid("019d2c7d-4000-7000-8000-" + suffix);
            UUID eventId = uuid("019d2c7d-4000-7000-8001-" + suffix);
            UUID snapshotId = uuid("019d2c7d-4000-7000-8002-" + suffix);
            DataBatchStatus status = switch (kind) {
                case ASSESSED_FAILED -> DataBatchStatus.QUALITY_FAILED;
                case ASSESSED_PASSED -> DataBatchStatus.QUALITY_PASSED;
                case PUBLISHED -> DataBatchStatus.PUBLISHED;
            };
            QualityOverallResult result = kind == UpstreamQualityEventKind.ASSESSED_FAILED
                    ? QualityOverallResult.QUALITY_FAILED
                    : QualityOverallResult.QUALITY_PASSED;
            UpstreamQualityEvent event = new UpstreamQualityEvent(
                    eventId, "urn:scholarsense:ingestion-quality", kind.eventType(),
                    kind.schemaVersion(), batchId, sourceId,
                    sourceVersion, uuid("019d2c7d-4000-7000-8003-000000000401"),
                    null, kind.batchAggregateVersion(), status, snapshotId,
                    "sha256:" + "4".repeat(64), 3, result,
                    "sha256:" + "5".repeat(64), "opaque-watermark",
                    new BatchObservationWindow(
                            Instant.parse("2026-08-09T00:00:00Z"),
                            Instant.parse("2026-08-10T00:00:00Z")),
                    Instant.parse("2026-08-10T00:00:00Z"),
                    "CAMPUS-ACCESS-SLICE-1.0.0", "sha256:" + "6".repeat(64),
                    "QMDP-1.0.0", "sha256:" + "7".repeat(64),
                    "QG-1.0.0", "sha256:" + "8".repeat(64),
                    Instant.parse("2026-08-10T00:00:00Z"),
                    Instant.parse("2026-08-10T00:01:00Z"),
                    "11111111111111111111111111111111",
                    "sha256:" + Integer.toHexString((int) sourceVersion).repeat(64).substring(0, 64));
            snapshot = snapshotFor(event);
            return event;
        }

        private UpstreamQualityEvent publishedFor(UpstreamQualityEvent assessed) {
            UpstreamQualityEvent event = assessed.asPublished(
                    uuid("019d2c7d-4000-7000-8004-000000000401"),
                    Instant.parse("2026-08-10T00:02:00Z"),
                    "sha256:" + "a".repeat(64));
            snapshot = snapshotFor(event);
            return event;
        }

        private QualityEligibilitySnapshotEvidence snapshotFor(UpstreamQualityEvent event) {
            return new QualityEligibilitySnapshotEvidence(
                    event.snapshotId(), event.batchId(), event.sourceId(),
                    event.snapshotResult(), event.observationWindow(), event.cutoffAt(),
                    event.watermark(), event.manifestDigest(), event.sourceSchemaVersion(),
                    event.sourceSchemaDigest(), event.qmdpVersion(), event.qmdpDigest(),
                    event.qualityGateVersion(), event.qualityGateDigest(),
                    "QSHM-1.0.0", "sha256:" + "b".repeat(64), event.lineageId(),
                    event.effectiveAt(), event.snapshotImmutableHash(), List.of(
                            new QualityFuseFormulaBoundaryEvidence(
                                    "completeness", "QMDP-1.0.0/test/completeness", "1.0.0",
                                    event.snapshotResult() == QualityOverallResult.QUALITY_FAILED
                                            ? "failed" : "passed",
                                    true, event.snapshotResult() == QualityOverallResult.QUALITY_FAILED
                                            ? 97 : 100,
                                    100,
                                    event.snapshotResult() == QualityOverallResult.QUALITY_FAILED
                                            ? 9700L : 10000L,
                                    "basis-point", ">=", 99, 100, "inclusive",
                                    event.snapshotResult() != QualityOverallResult.QUALITY_FAILED)));
        }
    }

    private static final class InMemoryTransaction
            implements QualityEligibilityEventTransactionPort {
        private final Map<String, QualityEligibilityProcessingState> states =
                new LinkedHashMap<>();
        private final List<DependencyQualityState> dependencyStates = new ArrayList<>();
        private final Map<String, QualityEligibilityCurrentState> currentEligibilities =
                new LinkedHashMap<>();
        private final Map<String, QualityFuseEpisodeState> activeEpisodes =
                new LinkedHashMap<>();
        private final Map<String, Long> latestGenerations = new LinkedHashMap<>();
        private final Map<String, RecoveryObservationProgressState> recoveryObservations =
                new LinkedHashMap<>();

        @Override
        public QualityEligibilityMutation transact(
                UpstreamQualityEvent event,
                Function<QualityEligibilityProcessingState, QualityEligibilityMutation> planner) {
            QualityEligibilityProcessingState state = states.getOrDefault(
                    event.sourceId(), QualityEligibilityProcessingState.empty());
            state = new QualityEligibilityProcessingState(
                    state.inboxEntries(), state.currentInbox(), state.cursor(),
                    state.pendingPair(), dependencyStates, currentEligibilities,
                    activeEpisodes, latestGenerations, recoveryObservations);
            QualityEligibilityInboxEntry existing = state.inboxEntries().get(event.eventId());
            state = state.withCurrentInbox(existing);
            QualityEligibilityMutation mutation = planner.apply(state);

            Map<UUID, QualityEligibilityInboxEntry> inbox = new LinkedHashMap<>(
                    state.inboxEntries());
            inbox.put(event.eventId(), new QualityEligibilityInboxEntry(
                    event.payloadDigest(), mutation.outcome(), mutation.fuseTaskPlan()));
            List<DependencyQualityState> dependencies = new ArrayList<>(
                    state.dependencyStates());
            if (mutation.dependencyState() != null) {
                dependencies.removeIf(value -> value.dependencyId().equals(
                        mutation.dependencyState().dependencyId()));
                dependencies.add(mutation.dependencyState());
                dependencyStates.removeIf(value -> value.dependencyId().equals(
                        mutation.dependencyState().dependencyId()));
                dependencyStates.add(mutation.dependencyState());
            }
            states.put(event.sourceId(), new QualityEligibilityProcessingState(
                    inbox, null,
                    mutation.cursor() == null ? state.cursor() : mutation.cursor(),
                    mutation.pendingPair(), dependencies, currentEligibilities,
                    activeEpisodes, latestGenerations, recoveryObservations));
            long aggregateVersion = 1;
            for (RuleEligibilityDecision decision : mutation.decisions()) {
                String key = decision.rule().ruleVersion().businessKey(
                        "RULE-DEPENDENCY-REGISTRY-1.0.0");
                QualityEligibilityCurrentState prior = currentEligibilities.get(key);
                aggregateVersion = prior == null ? 1 : prior.aggregateVersion() + 1;
                currentEligibilities.put(key, new QualityEligibilityCurrentState(
                        prior == null
                                ? uuid("019d2c7d-4000-7000-8007-000000000401")
                                : prior.eligibilityId(),
                        decision.rule().ruleVersion(), aggregateVersion,
                        decision.decision().status(), decision.decision().reason()));
            }
            if (mutation.fuseTaskPlan() != null
                    && mutation.fuseTaskPlan().action() != QualityFuseTaskAction.NONE) {
                QualityFuseTaskPlan plan = mutation.fuseTaskPlan();
                String episodeKey = QualityEligibilityProcessingState.episodeKey(
                        plan.sourceId(), plan.dependencyId());
                QualityFuseEpisodeState prior = activeEpisodes.get(episodeKey);
                activeEpisodes.put(episodeKey, new QualityFuseEpisodeState(
                        plan.episodeId(), plan.recoveryTaskId(), plan.workItemKey(),
                        plan.workItemKeyVersion(), plan.sourceId(), plan.dependencyId(),
                        plan.episodeGeneration(),
                        prior == null ? 1 : prior.aggregateVersion() + 1));
                latestGenerations.put(episodeKey, plan.episodeGeneration());
            }
            if (mutation.recoveryObservationProgress() != null) {
                RecoveryObservationProgressState progress =
                        mutation.recoveryObservationProgress();
                recoveryObservations.put(progress.key(), progress);
            }
            return mutation;
        }

        private void seedEligibility(
                String ruleId,
                QualityEligibilityStatus status,
                QualityEligibilityReason reason) {
            RuleVersionIdentity rule = new RuleVersionIdentity(ruleId, "1.0.0");
            currentEligibilities.put(rule.businessKey("RULE-DEPENDENCY-REGISTRY-1.0.0"),
                    new QualityEligibilityCurrentState(
                            uuid("019d2c7d-4000-7000-8007-000000000402"),
                            rule, 1, status, reason));
        }

        private void seedEpisode(String sourceId, String dependencyId) {
            String key = QualityEligibilityProcessingState.episodeKey(sourceId, dependencyId);
            activeEpisodes.put(key, new QualityFuseEpisodeState(
                    uuid("019d2c7d-4000-7000-8008-000000000401"),
                    uuid("019d2c7d-4000-7000-8009-000000000401"),
                    "qf:" + "1".repeat(64), "k3", sourceId, dependencyId, 1, 1));
            latestGenerations.put(key, 1L);
        }

        private void seedObservation(String sourceId, String dependencyId) {
            RecoveryObservationProgressState progress =
                    RecoveryObservationProgressState.start(
                            uuid("019d2c7d-4000-7000-8006-000000000401"),
                            1, sourceId, dependencyId,
                            Instant.parse("2026-08-10T00:00:00Z"));
            recoveryObservations.put(progress.key(), progress);
        }
    }

    private static RuleDependencyRegistry registry() {
        List<RuleDependencyDefinition> definitions = List.of(
                rule("ACC-SAFE-001", "DEP-P0-CAMPUS-ACCESS-001"),
                rule("ACC-SAFE-002", "DEP-P0-DORM-ACCESS-001"),
                rule("ECON-012", "DEP-P0-CONSUMPTION-001"),
                rule("NIGHT-001", "DEP-P1-NETWORK-001"),
                rule("ACADEMIC-001", "DEP-P1-ACADEMIC-001"));
        return QualityEligibilityTestRegistry.completeProductionRegistry(definitions);
    }

    private static RuleDependencyDefinition rule(String ruleId, String firstDependency) {
        String sourceId = switch (firstDependency) {
            case "DEP-P0-CAMPUS-ACCESS-001" -> "SRC-P0-CAMPUS-ACCESS-001";
            case "DEP-P0-DORM-ACCESS-001" -> "SRC-P0-DORM-ACCESS-001";
            case "DEP-P0-CONSUMPTION-001" -> "SRC-P0-CARD-001";
            case "DEP-P1-NETWORK-001" -> "SRC-P1-NETWORK-001";
            default -> "SRC-P1-ACADEMIC-001";
        };
        return new RuleDependencyDefinition(
                new RuleVersionIdentity(ruleId, "1.0.0"), DependencyOperator.ALL_OF,
                null, List.of(new RuleDependencyMember(
                        sourceId, "SOURCE-1.0.0", firstDependency, "1.0.0",
                        DependencyRequirement.REQUIRED, "primary")));
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
