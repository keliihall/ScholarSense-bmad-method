package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.PendingQualityPair;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityBackfillRequest;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityCurrentState;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityCursor;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityCursorStage;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityEventTransactionPort;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityInboxEntry;
import cn.edu.suda.scholarsense.ingestionquality.application.IngestionQualityApplicationException;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityMutation;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityProcessingOutcome;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityProcessingState;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilityQuarantine;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityEligibilitySnapshotEvidence;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseEpisodeState;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseEligibilityEvidence;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseFormulaBoundaryEvidence;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseMemberEvidence;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseTaskPlan;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityFuseWorkloadAuthorizationGuard;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryObservationProgressState;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryObservationProgressStatus;
import cn.edu.suda.scholarsense.ingestionquality.application.RuleEligibilityDecision;
import cn.edu.suda.scholarsense.ingestionquality.application.UpstreamQualityEvent;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchWorkloadAuthorizationEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyQualityState;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyRequirement;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityReason;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityFuseTaskAction;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleVersionIdentity;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Loads state, plans, and commits inbox/cursor/facts/audit/outbox in one local transaction. */
public final class JdbcQualityEligibilityEventTransactionAdapter
        implements QualityEligibilityEventTransactionPort {
    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final ObjectMapper json;
    private final QualityFuseWorkloadAuthorizationGuard authorization;
    private final TrustedTimeSource trustedTime;

    public JdbcQualityEligibilityEventTransactionAdapter(
            JdbcTemplate jdbc,
            TransactionOperations transactions,
            ObjectMapper json,
            QualityFuseWorkloadAuthorizationGuard authorization,
            TrustedTimeSource trustedTime) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.json = Objects.requireNonNull(json);
        this.authorization = Objects.requireNonNull(authorization);
        this.trustedTime = Objects.requireNonNull(trustedTime);
    }

    @Override
    public QualityEligibilityMutation transact(
            UpstreamQualityEvent event,
            Function<QualityEligibilityProcessingState, QualityEligibilityMutation> planner) {
        Objects.requireNonNull(event);
        Objects.requireNonNull(planner);
        DataBatchWorkloadAuthorizationEvidence captured = capture(event);
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                String persisted = jdbc.queryForObject("""
                        select ingestion_quality.iq_load_quality_eligibility_processing_state_v2(?,?)::text
                        """, String.class, event.eventId(), event.sourceId());
                QualityEligibilityProcessingState state = decodeState(event.eventId(), persisted);
                QualityEligibilityMutation mutation = Objects.requireNonNull(planner.apply(state));
                Instant revalidatedAt;
                try {
                    revalidatedAt = trustedInstant();
                    authorization.revalidate(captured, revalidatedAt);
                } catch (IngestionQualityApplicationException rejected) {
                    throw new AuthorizationFailure(rejected);
                }
                String response = jdbc.queryForObject("""
                        select ingestion_quality.iq_accept_quality_eligibility_event_v2(
                            ?,?,?,?,?::jsonb)::text
                        """, String.class, event.eventId(), event.sourceId(),
                        event.sourceVersion(), event.payloadDigest(),
                        encodeMutation(event, mutation, captured, revalidatedAt));
                PersistedResponse persistedResponse = decodeResponse(response);
                if (!wire(mutation.outcome()).equals(wire(persistedResponse.outcome()))
                        && persistedResponse.outcome()
                        != QualityEligibilityProcessingOutcome.DUPLICATE) {
                    throw persistenceInvalid();
                }
                if (persistedResponse.outcome() == mutation.outcome()
                        && Objects.equals(persistedResponse.fuseTaskPlan(),
                        mutation.fuseTaskPlan())) {
                    return mutation;
                }
                return new QualityEligibilityMutation(
                        persistedResponse.outcome(), state.cursor(), state.pendingPair(), null,
                        List.of(), null, null, null, persistedResponse.fuseTaskPlan());
            }));
        } catch (AuthorizationFailure rejected) {
            recordRejection(event, rejected.failure);
            throw rejected.failure;
        }
    }

    private DataBatchWorkloadAuthorizationEvidence capture(UpstreamQualityEvent event) {
        try {
            return authorization.capture(trustedInstant());
        } catch (IngestionQualityApplicationException rejected) {
            recordRejection(event, rejected);
            throw rejected;
        }
    }

    private Instant trustedInstant() {
        try {
            return Objects.requireNonNull(trustedTime.now()).instant();
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new IngestionQualityApplicationException(
                    "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", unavailable);
        }
    }

    private void recordRejection(
            UpstreamQualityEvent event,
            IngestionQualityApplicationException rejected) {
        String result = switch (rejected.code()) {
            case "INGESTION_QUALITY_FORBIDDEN" -> "denied";
            default -> "dependency-unavailable";
        };
        try {
            jdbc.query("""
                    select ingestion_quality.iq_append_quality_fuse_rejection_audit(
                           ?,?,?,?)
                    """, row -> null, event.eventId(), event.sourceVersion(),
                    result, event.traceId());
        } catch (RuntimeException auditFailure) {
            throw new IngestionQualityApplicationException(
                    "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", auditFailure);
        }
    }

    private QualityEligibilityProcessingState decodeState(UUID eventId, String persisted) {
        try {
            JsonNode root = json.readTree(Objects.requireNonNull(persisted));
            JsonNode inboxNode = root.get("currentInbox");
            QualityEligibilityInboxEntry inbox = null;
            Map<UUID, QualityEligibilityInboxEntry> entries = new LinkedHashMap<>();
            if (present(inboxNode)) {
                inbox = new QualityEligibilityInboxEntry(
                        text(inboxNode, "payloadDigest"),
                        outcome(text(inboxNode, "outcome")),
                        decodeFusePlan(inboxNode.get("fuseTaskPlan")));
                entries.put(eventId, inbox);
            }
            JsonNode cursorNode = root.get("cursor");
            QualityEligibilityCursor cursor = present(cursorNode)
                    ? new QualityEligibilityCursor(
                            text(cursorNode, "sourceId"), text(cursorNode, "dependencyId"),
                            number(cursorNode, "sourceVersion"), uuid(cursorNode, "lineageId"),
                            number(cursorNode, "lineageRevision"), uuid(cursorNode, "batchId"),
                            stage(text(cursorNode, "stage")),
                            bool(cursorNode, "paused"), number(cursorNode, "aggregateVersion"))
                    : null;
            JsonNode pairNode = root.get("pendingPair");
            PendingQualityPair pair = present(pairNode)
                    ? new PendingQualityPair(
                            uuid(pairNode, "assessedEventId"), uuid(pairNode, "batchId"),
                            uuid(pairNode, "snapshotId"),
                            text(pairNode, "snapshotImmutableHash"),
                            number(pairNode, "sourceVersion"), uuid(pairNode, "lineageId"))
                    : null;
            ArrayList<DependencyQualityState> dependencies = new ArrayList<>();
            JsonNode states = root.get("dependencyStates");
            if (states == null || !states.isArray()) throw persistenceInvalid();
            for (JsonNode dependency : states) {
                dependencies.add(new DependencyQualityState(
                        text(dependency, "sourceId"), number(dependency, "sourceVersion"),
                        uuid(dependency, "lineageId"),
                        number(dependency, "lineageRevision"),
                        text(dependency, "dependencyId"),
                        number(dependency, "dependencyVersion"),
                        eligibilityStatus(text(dependency, "status")),
                        bool(dependency, "versionContinuous"),
                        text(dependency, "watermark")));
            }
            LinkedHashMap<String, QualityEligibilityCurrentState> current =
                    new LinkedHashMap<>();
            JsonNode currentNode = root.get("currentEligibilities");
            if (currentNode == null || !currentNode.isObject()) throw persistenceInvalid();
            currentNode.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                RuleVersionIdentity rule = new RuleVersionIdentity(
                        text(value, "ruleId"), text(value, "ruleVersion"));
                QualityEligibilityCurrentState state = new QualityEligibilityCurrentState(
                        uuid(value, "eligibilityId"), rule, number(value, "aggregateVersion"),
                        eligibilityStatus(text(value, "status")),
                        eligibilityReason(text(value, "reasonCode")));
                if (!entry.getKey().equals(state.key("RULE-DEPENDENCY-REGISTRY-1.0.0"))) {
                    throw persistenceInvalid();
                }
                current.put(entry.getKey(), state);
            });
            LinkedHashMap<String, QualityFuseEpisodeState> episodes = new LinkedHashMap<>();
            JsonNode episodesNode = root.get("activeEpisodes");
            if (episodesNode == null || !episodesNode.isObject()) throw persistenceInvalid();
            episodesNode.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                QualityFuseEpisodeState episode = new QualityFuseEpisodeState(
                        uuid(value, "episodeId"), uuid(value, "recoveryTaskId"),
                        text(value, "workItemKey"), text(value, "workItemKeyVersion"),
                        text(value, "sourceId"),
                        text(value, "dependencyId"), number(value, "generation"),
                        number(value, "aggregateVersion"));
                if (!entry.getKey().equals(episode.key())) throw persistenceInvalid();
                episodes.put(entry.getKey(), episode);
            });
            LinkedHashMap<String, Long> generations = new LinkedHashMap<>();
            JsonNode generationsNode = root.get("latestEpisodeGenerations");
            if (generationsNode == null || !generationsNode.isObject()) {
                throw persistenceInvalid();
            }
            generationsNode.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                if (!value.isIntegralNumber() || !value.canConvertToLong()
                        || value.longValue() < 1) throw persistenceInvalid();
                generations.put(entry.getKey(), value.longValue());
            });
            LinkedHashMap<String, RecoveryObservationProgressState> observations =
                    new LinkedHashMap<>();
            JsonNode observationsNode = root.get("recoveryObservations");
            if (observationsNode != null && !observationsNode.isNull()) {
                if (!observationsNode.isObject()) throw persistenceInvalid();
                observationsNode.properties().forEach(entry -> {
                    JsonNode value = entry.getValue();
                    RecoveryObservationProgressState progress =
                            new RecoveryObservationProgressState(
                                    uuid(value, "recoveryId"), number(value, "generation"),
                                    text(value, "sourceId"), text(value, "dependencyId"),
                                    instant(value, "recoveringStartedAt"),
                                    number(value, "lastSourceVersionOrdinal"),
                                    number(value, "lastLineageRevision"),
                                    integer(value, "consecutivePassedBatches"),
                                    optionalText(value, "watermark"),
                                    instant(value, "lastObservedAt"),
                                    observationStatus(text(value, "status")),
                                    number(value, "aggregateVersion"));
                    if (!entry.getKey().equals(progress.key())) throw persistenceInvalid();
                    observations.put(entry.getKey(), progress);
                });
            }
            return new QualityEligibilityProcessingState(
                    entries, inbox, cursor, pair, dependencies,
                    current, episodes, generations, observations);
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalStateException state
                    && "INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID".equals(
                    state.getMessage())) throw state;
            throw persistenceInvalid();
        }
    }

    private String encodeMutation(
            UpstreamQualityEvent event,
            QualityEligibilityMutation mutation,
            DataBatchWorkloadAuthorizationEvidence authorizationEvidence,
            Instant revalidatedAt) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("outcome", wire(mutation.outcome()));
        root.put("cursor", cursor(mutation.cursor()));
        root.put("pendingPair", pair(mutation.pendingPair()));
        root.put("dependencyState", dependency(mutation.dependencyState()));
        root.put("decisions", mutation.decisions().stream().map(this::decision).toList());
        root.put("backfillRequest", backfill(mutation.backfillRequest()));
        root.put("quarantine", quarantine(mutation.quarantine()));
        root.put("snapshotEvidence", snapshot(mutation.snapshotEvidence()));
        root.put("fuseTaskPlan", fuseTaskPlan(
                event, mutation, authorizationEvidence, revalidatedAt));
        root.put("recoveryObservationProgress", observation(
                mutation.recoveryObservationProgress()));
        root.put("occurredAt", event.occurredAt().toString());
        root.put("effectiveAt", event.effectiveAt().toString());
        root.put("traceId", event.traceId());
        try {
            return json.writeValueAsString(root);
        } catch (RuntimeException exception) {
            throw persistenceInvalid();
        }
    }

    private Map<String, Object> cursor(QualityEligibilityCursor value) {
        if (value == null) return null;
        return map(
                "sourceId", value.sourceId(), "dependencyId", value.dependencyId(),
                "sourceVersion", value.sourceVersion(), "lineageId", value.lineageId(),
                "lineageRevision", value.lineageRevision(), "batchId", value.batchId(),
                "stage", switch (value.stage()) {
                    case PENDING_PUBLICATION -> "pending-publication";
                    case TERMINAL -> "terminal";
                },
                "paused", value.paused(), "aggregateVersion", value.aggregateVersion());
    }

    private Map<String, Object> pair(PendingQualityPair value) {
        if (value == null) return null;
        return map(
                "assessedEventId", value.assessedEventId(), "batchId", value.batchId(),
                "snapshotId", value.snapshotId(),
                "snapshotImmutableHash", value.snapshotImmutableHash(),
                "sourceVersion", value.sourceVersion(), "lineageId", value.lineageId());
    }

    private Map<String, Object> dependency(DependencyQualityState value) {
        if (value == null) return null;
        return map(
                "sourceId", value.sourceId(), "sourceVersion", value.sourceVersion(),
                "lineageId", value.lineageId(), "lineageRevision", value.lineageRevision(),
                "dependencyId", value.dependencyId(),
                "dependencyVersion", value.dependencyVersion(),
                "status", value.status().wireValue(),
                "versionContinuous", value.versionContinuous(), "watermark", value.watermark());
    }

    private Map<String, Object> observation(RecoveryObservationProgressState value) {
        if (value == null) return null;
        return map(
                "recoveryId", value.recoveryId(), "generation", value.generation(),
                "sourceId", value.sourceId(), "dependencyId", value.dependencyId(),
                "recoveringStartedAt", value.recoveringStartedAt().toString(),
                "lastSourceVersionOrdinal", value.lastSourceVersionOrdinal(),
                "lastLineageRevision", value.lastLineageRevision(),
                "consecutivePassedBatches", value.consecutivePassedBatches(),
                "watermark", value.watermark(),
                "lastObservedAt", value.lastObservedAt().toString(),
                "status", switch (value.status()) {
                    case OBSERVING -> "observing";
                    case RELAPSED -> "relapsed";
                    default -> throw persistenceInvalid();
                },
                "aggregateVersion", value.aggregateVersion());
    }

    private Map<String, Object> decision(RuleEligibilityDecision value) {
        return map(
                "ruleId", value.rule().ruleVersion().ruleId(),
                "ruleVersion", value.rule().ruleVersion().ruleVersion(),
                "status", value.decision().status().wireValue(),
                "reasonCode", value.decision().reason().name(),
                "failedMembers", value.decision().failedMembers());
    }

    private Map<String, Object> fuseTaskPlan(
            UpstreamQualityEvent event,
            QualityEligibilityMutation mutation,
            DataBatchWorkloadAuthorizationEvidence authorizationEvidence,
            Instant revalidatedAt) {
        QualityFuseTaskPlan value = mutation.fuseTaskPlan();
        if (value == null) return null;
        return map(
                "action", taskAction(value.action()),
                "episodeId", value.episodeId(),
                "recoveryTaskId", value.recoveryTaskId(),
                "workItemKey", value.workItemKey(),
                "workItemKeyVersion", value.workItemKeyVersion(),
                "episodeGeneration", value.episodeGeneration(),
                "expectedEpisodeAggregateVersion", value.expectedEpisodeAggregateVersion(),
                "sourceId", value.sourceId(),
                "sourceVersion", value.sourceVersion(),
                "dependencyId", value.dependencyId(),
                "dependencyVersion", value.dependencyVersion(),
                "batchId", value.batchId(),
                "snapshotId", value.snapshotId(),
                "snapshotHash", value.snapshotHash(),
                "qualityGateVersion", value.qualityGateVersion(),
                "qualityGateDigest", value.qualityGateDigest(),
                "qmdpVersion", value.qmdpVersion(),
                "qmdpDigest", value.qmdpDigest(),
                "qshmVersion", value.qshmVersion(),
                "qshmDigest", value.qshmDigest(),
                "lineageId", value.lineageId(),
                "watermark", value.watermark(),
                "affectedRules", value.affectedRules().stream().map(rule -> map(
                        "ruleId", rule.ruleId(), "ruleVersion", rule.ruleVersion())).toList(),
                "eligibilityEvidence", value.eligibilityEvidence().stream()
                        .map(this::eligibilityEvidence).toList(),
                "formulaEvidence", value.formulaEvidence().stream()
                        .map(JdbcQualityEligibilityEventTransactionAdapter::formulaEvidence)
                        .toList(),
                "fuseBusinessKey", value.fuseBusinessKey(),
                "commandBodyDigest", value.commandBodyDigest(),
                "effectiveAt", value.effectiveAt().toString(),
                "occurredAt", value.occurredAt().toString(),
                "transitions", mutation.decisions().stream()
                        .filter(item -> item.transition() != null)
                        .map(item -> map(
                                "ruleId", item.rule().ruleVersion().ruleId(),
                                "ruleVersion", item.rule().ruleVersion().ruleVersion(),
                                "priorState", item.transition().priorState().wireValue(),
                                "evaluatedState", item.evaluatedDecision().status().wireValue(),
                                "appliedState", item.decision().status().wireValue(),
                                "reasonCode", item.decision().reason().name(),
                                "evaluatedReasonCode",
                                item.evaluatedDecision().reason().name()))
                        .toList(),
                "authorizationEvidence", map(
                        "environment", authorizationEvidence.environment(),
                        "serviceRef", authorizationEvidence.principalRef(),
                        "audience", authorizationEvidence.audience(),
                        "capability", authorizationEvidence.capabilities().iterator().next(),
                        "mtlsSanUriRef", authorizationEvidence.mtlsSanUriRef(),
                        "authorizationGeneration",
                                authorizationEvidence.authorizationGeneration(),
                        "policyVersion", authorizationEvidence.policyVersion(),
                        "policyDigest", authorizationEvidence.policyDigest(),
                        "effectiveAt", authorizationEvidence.effectiveAt().toString(),
                        "trustedAt", revalidatedAt.toString(),
                        "expiresAt", authorizationEvidence.expiresAt().toString()));
    }

    private Map<String, Object> eligibilityEvidence(QualityFuseEligibilityEvidence value) {
        return map(
                "eligibilityId", value.eligibilityId(),
                "businessKey", value.businessKey(),
                "aggregateVersion", value.aggregateVersion(),
                "ruleId", value.ruleVersion().ruleId(),
                "ruleVersion", value.ruleVersion().ruleVersion(),
                "memberSetDigest", value.memberSetDigest(),
                "registryVersion", value.registryVersion(),
                "registryDigest", value.registryDigest(),
                "dccVersion", value.dccVersion(),
                "dccDigest", value.dccDigest(),
                "ruleCatalogVersion", value.ruleCatalogVersion(),
                "ruleCatalogDigest", value.ruleCatalogDigest(),
                "operator", operator(value.operator()),
                "threshold", value.threshold(),
                "priorState", value.priorState().wireValue(),
                "evaluatedState", value.evaluatedState().wireValue(),
                "appliedState", value.appliedState().wireValue(),
                "reasonCode", value.reason().name(),
                "members", value.members().stream().map(member -> map(
                        "sourceId", member.sourceId(),
                        "sourceContractVersion", member.sourceContractVersion(),
                        "sourceVersion", member.sourceVersion(),
                        "dependencyId", member.dependencyId(),
                        "dependencyContractVersion", member.dependencyContractVersion(),
                        "dependencyVersion", member.dependencyVersion(),
                        "requirement", member.requirement().name().toLowerCase(
                                java.util.Locale.ROOT),
                        "compositionGroup", member.compositionGroup(),
                        "state", member.state().wireValue(),
                        "versionContinuous", member.versionContinuous(),
                        "watermark", member.watermark(),
                        "failed", member.failed())).toList());
    }

    private static Map<String, Object> formulaEvidence(
            QualityFuseFormulaBoundaryEvidence value) {
        return map(
                "metricId", value.metricId(), "formulaId", value.formulaId(),
                "formulaVersion", value.formulaVersion(), "result", value.result(),
                "applicable", value.applicable(), "numerator", value.numerator(),
                "denominator", value.denominator(),
                "valueBasisPoints", value.valueBasisPoints(), "unit", value.unit(),
                "operator", value.operator(),
                "thresholdNumerator", value.thresholdNumerator(),
                "thresholdDenominator", value.thresholdDenominator(),
                "boundary", value.boundary(),
                "comparisonResult", value.comparisonResult());
    }

    private Map<String, Object> backfill(QualityEligibilityBackfillRequest value) {
        if (value == null) return null;
        return map(
                "sourceId", value.sourceId(),
                "expectedSourceVersion", value.expectedSourceVersion(),
                "actualSourceVersion", value.actualSourceVersion(),
                "cursorAggregateVersion", value.cursorAggregateVersion());
    }

    private Map<String, Object> quarantine(QualityEligibilityQuarantine value) {
        if (value == null) return null;
        return map(
                "eventId", value.eventId(), "sourceId", value.sourceId(),
                "reasonCode", value.reasonCode(), "payloadDigest", value.payloadDigest());
    }

    private Map<String, Object> snapshot(QualityEligibilitySnapshotEvidence value) {
        if (value == null) return null;
        return map(
                "snapshotId", value.snapshotId(), "batchId", value.batchId(),
                "sourceId", value.sourceId(), "result", value.result().wireValue(),
                "watermark", value.watermark(), "manifestDigest", value.manifestDigest(),
                "sourceSchemaVersion", value.sourceSchemaVersion(),
                "sourceSchemaDigest", value.sourceSchemaDigest(),
                "qmdpVersion", value.qmdpVersion(), "qmdpDigest", value.qmdpDigest(),
                "qualityGateVersion", value.qualityGateVersion(),
                "qualityGateDigest", value.qualityGateDigest(),
                "qshmVersion", value.qshmVersion(), "qshmDigest", value.qshmDigest(),
                "lineageId", value.lineageId(), "effectiveAt", value.effectiveAt().toString(),
                "immutableHash", value.immutableHash());
    }

    private static LinkedHashMap<String, Object> map(Object... entries) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    private static boolean present(JsonNode node) {
        return node != null && !node.isNull();
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) throw persistenceInvalid();
        return value.stringValue();
    }

    private static long number(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw persistenceInvalid();
        }
        return value.longValue();
    }

    private static int integer(JsonNode parent, String field) {
        try {
            return Math.toIntExact(number(parent, field));
        } catch (ArithmeticException exception) {
            throw persistenceInvalid();
        }
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw persistenceInvalid();
        return value.stringValue();
    }

    private static Instant instant(JsonNode parent, String field) {
        try {
            return Instant.parse(text(parent, field));
        } catch (RuntimeException exception) {
            throw persistenceInvalid();
        }
    }

    private static RecoveryObservationProgressStatus observationStatus(String value) {
        return switch (value) {
            case "observing" -> RecoveryObservationProgressStatus.OBSERVING;
            case "ready" -> RecoveryObservationProgressStatus.READY;
            case "relapsed" -> RecoveryObservationProgressStatus.RELAPSED;
            case "policy-drift" -> RecoveryObservationProgressStatus.POLICY_DRIFT;
            case "finalized" -> RecoveryObservationProgressStatus.FINALIZED;
            default -> throw persistenceInvalid();
        };
    }

    private static Long nullableNumber(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value == null || value.isNull() ? null : number(parent, field);
    }

    private static Boolean nullableBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isBoolean()) throw persistenceInvalid();
        return value.booleanValue();
    }

    private static boolean bool(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) throw persistenceInvalid();
        return value.booleanValue();
    }

    private static UUID uuid(JsonNode parent, String field) {
        try {
            return UUID.fromString(text(parent, field));
        } catch (IllegalArgumentException exception) {
            throw persistenceInvalid();
        }
    }

    private static QualityEligibilityCursorStage stage(String value) {
        return switch (value) {
            case "pending-publication" -> QualityEligibilityCursorStage.PENDING_PUBLICATION;
            case "terminal" -> QualityEligibilityCursorStage.TERMINAL;
            default -> throw persistenceInvalid();
        };
    }

    private static QualityEligibilityStatus eligibilityStatus(String value) {
        for (QualityEligibilityStatus status : QualityEligibilityStatus.values()) {
            if (status.wireValue().equals(value)) return status;
        }
        throw persistenceInvalid();
    }

    private static QualityEligibilityReason eligibilityReason(String value) {
        try {
            return QualityEligibilityReason.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw persistenceInvalid();
        }
    }

    private PersistedResponse decodeResponse(String response) {
        try {
            JsonNode root = json.readTree(Objects.requireNonNull(response));
            return new PersistedResponse(
                    outcome(text(root, "outcome")),
                    decodeFusePlan(root.get("fuseTaskPlan")));
        } catch (RuntimeException exception) {
            throw persistenceInvalid();
        }
    }

    private QualityFuseTaskPlan decodeFusePlan(JsonNode value) {
        if (!present(value)) return null;
        JsonNode affected = value.get("affectedRules");
        if (affected == null || !affected.isArray()) throw persistenceInvalid();
        ArrayList<RuleVersionIdentity> rules = new ArrayList<>();
        for (JsonNode rule : affected) {
            rules.add(new RuleVersionIdentity(
                    text(rule, "ruleId"), text(rule, "ruleVersion")));
        }
        JsonNode eligibilityNode = value.get("eligibilityEvidence");
        if (eligibilityNode == null || !eligibilityNode.isArray()) throw persistenceInvalid();
        ArrayList<QualityFuseEligibilityEvidence> eligibilities = new ArrayList<>();
        for (JsonNode evidence : eligibilityNode) {
            JsonNode membersNode = evidence.get("members");
            if (membersNode == null || !membersNode.isArray()) throw persistenceInvalid();
            ArrayList<QualityFuseMemberEvidence> members = new ArrayList<>();
            for (JsonNode member : membersNode) {
                members.add(new QualityFuseMemberEvidence(
                        text(member, "sourceId"), text(member, "sourceContractVersion"),
                        number(member, "sourceVersion"), text(member, "dependencyId"),
                        text(member, "dependencyContractVersion"),
                        number(member, "dependencyVersion"),
                        requirement(text(member, "requirement")),
                        text(member, "compositionGroup"),
                        eligibilityStatus(text(member, "state")),
                        bool(member, "versionContinuous"), text(member, "watermark"),
                        bool(member, "failed")));
            }
            Long threshold = nullableNumber(evidence, "threshold");
            eligibilities.add(new QualityFuseEligibilityEvidence(
                    uuid(evidence, "eligibilityId"), text(evidence, "businessKey"),
                    number(evidence, "aggregateVersion"),
                    new RuleVersionIdentity(
                            text(evidence, "ruleId"), text(evidence, "ruleVersion")),
                    text(evidence, "memberSetDigest"),
                    text(evidence, "registryVersion"), text(evidence, "registryDigest"),
                    text(evidence, "dccVersion"), text(evidence, "dccDigest"),
                    text(evidence, "ruleCatalogVersion"),
                    text(evidence, "ruleCatalogDigest"),
                    operator(text(evidence, "operator")),
                    threshold == null ? null : Math.toIntExact(threshold),
                    eligibilityStatus(text(evidence, "priorState")),
                    eligibilityStatus(text(evidence, "evaluatedState")),
                    eligibilityStatus(text(evidence, "appliedState")),
                    eligibilityReason(text(evidence, "reasonCode")), members));
        }
        JsonNode formulaNode = value.get("formulaEvidence");
        if (formulaNode == null || !formulaNode.isArray()) throw persistenceInvalid();
        ArrayList<QualityFuseFormulaBoundaryEvidence> formulas = new ArrayList<>();
        for (JsonNode evidence : formulaNode) {
            formulas.add(new QualityFuseFormulaBoundaryEvidence(
                    text(evidence, "metricId"), text(evidence, "formulaId"),
                    text(evidence, "formulaVersion"), text(evidence, "result"),
                    bool(evidence, "applicable"), number(evidence, "numerator"),
                    number(evidence, "denominator"),
                    nullableNumber(evidence, "valueBasisPoints"), text(evidence, "unit"),
                    text(evidence, "operator"), number(evidence, "thresholdNumerator"),
                    number(evidence, "thresholdDenominator"), text(evidence, "boundary"),
                    nullableBoolean(evidence, "comparisonResult")));
        }
        return new QualityFuseTaskPlan(
                taskAction(text(value, "action")),
                uuid(value, "episodeId"), uuid(value, "recoveryTaskId"),
                text(value, "workItemKey"), text(value, "workItemKeyVersion"),
                number(value, "episodeGeneration"),
                number(value, "expectedEpisodeAggregateVersion"),
                text(value, "sourceId"), number(value, "sourceVersion"),
                text(value, "dependencyId"),
                number(value, "dependencyVersion"), uuid(value, "batchId"),
                uuid(value, "snapshotId"), text(value, "snapshotHash"),
                text(value, "qualityGateVersion"), text(value, "qualityGateDigest"),
                text(value, "qmdpVersion"), text(value, "qmdpDigest"),
                text(value, "qshmVersion"), text(value, "qshmDigest"),
                uuid(value, "lineageId"), text(value, "watermark"), rules,
                eligibilities, formulas, text(value, "fuseBusinessKey"),
                text(value, "commandBodyDigest"),
                Instant.parse(text(value, "effectiveAt")),
                Instant.parse(text(value, "occurredAt")));
    }

    private static String operator(DependencyOperator value) {
        return switch (value) {
            case ALL_OF -> "all-of";
            case ANY_OF -> "any-of";
            case THRESHOLD -> "threshold";
        };
    }

    private static DependencyOperator operator(String value) {
        return switch (value) {
            case "all-of" -> DependencyOperator.ALL_OF;
            case "any-of" -> DependencyOperator.ANY_OF;
            case "threshold" -> DependencyOperator.THRESHOLD;
            default -> throw persistenceInvalid();
        };
    }

    private static DependencyRequirement requirement(String value) {
        return switch (value) {
            case "required" -> DependencyRequirement.REQUIRED;
            case "optional" -> DependencyRequirement.OPTIONAL;
            default -> throw persistenceInvalid();
        };
    }

    private static String taskAction(QualityFuseTaskAction value) {
        return value.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static QualityFuseTaskAction taskAction(String value) {
        return switch (value) {
            case "create" -> QualityFuseTaskAction.CREATE;
            case "update" -> QualityFuseTaskAction.UPDATE;
            case "preserve" -> QualityFuseTaskAction.PRESERVE;
            default -> throw persistenceInvalid();
        };
    }

    private static QualityEligibilityProcessingOutcome outcome(String value) {
        return switch (value) {
            case "applied" -> QualityEligibilityProcessingOutcome.APPLIED;
            case "pending-publication" -> QualityEligibilityProcessingOutcome.PENDING_PUBLICATION;
            case "duplicate" -> QualityEligibilityProcessingOutcome.DUPLICATE;
            case "old" -> QualityEligibilityProcessingOutcome.OLD;
            case "gap" -> QualityEligibilityProcessingOutcome.GAP;
            case "poisoned" -> QualityEligibilityProcessingOutcome.POISONED;
            default -> throw persistenceInvalid();
        };
    }

    private static String wire(QualityEligibilityProcessingOutcome value) {
        return switch (value) {
            case APPLIED -> "applied";
            case PENDING_PUBLICATION -> "pending-publication";
            case DUPLICATE -> "duplicate";
            case OLD -> "old";
            case GAP -> "gap";
            case POISONED -> "poisoned";
        };
    }

    private static IllegalStateException persistenceInvalid() {
        return new IllegalStateException("INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID");
    }

    private record PersistedResponse(
            QualityEligibilityProcessingOutcome outcome,
            QualityFuseTaskPlan fuseTaskPlan) {}

    private static final class AuthorizationFailure extends RuntimeException {
        private final IngestionQualityApplicationException failure;

        private AuthorizationFailure(IngestionQualityApplicationException failure) {
            super(failure);
            this.failure = failure;
        }
    }
}
