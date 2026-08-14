package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecisionToken;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckRequest;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest;
import cn.edu.suda.scholarsense.identityaccess.api.FieldVisibility;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Source-owner authorization, current-generation recheck and audit-before-response boundary. */
public final class QualityRecoveryTaskQueryService {
    private static final Map<String, FieldVisibility> REQUIRED_PROJECTION = Map.of(
            "B", FieldVisibility.CLEAR, "I", FieldVisibility.MASKED,
            "C", FieldVisibility.HIDDEN, "S", FieldVisibility.HIDDEN,
            "E", FieldVisibility.CLEAR, "N", FieldVisibility.HIDDEN,
            "G", FieldVisibility.CLEAR, "T", FieldVisibility.CLEAR);
    private final QualityRecoveryTaskQueryPort tasks;
    private final CompositeAuthorizationPort authorization;
    private final CompositeAuthorizationRecheckPort recheck;
    private final QualityRecoveryTaskReadAuditPort audit;

    public QualityRecoveryTaskQueryService(
            QualityRecoveryTaskQueryPort tasks,
            CompositeAuthorizationPort authorization,
            CompositeAuthorizationRecheckPort recheck,
            QualityRecoveryTaskReadAuditPort audit) {
        this.tasks = java.util.Objects.requireNonNull(tasks);
        this.authorization = java.util.Objects.requireNonNull(authorization);
        this.recheck = java.util.Objects.requireNonNull(recheck);
        this.audit = java.util.Objects.requireNonNull(audit);
    }

    public List<QualityRecoveryTaskView> list(
            QualityRecoveryTaskQueryCriteria criteria,
            QualitySnapshotActorContext actor,
            String traceId) {
        if (criteria.sourceId() == null) throw forbidden();
        Binding ownerScope = authorizeOwnerScope(criteria.sourceId(), actor, traceId);
        if (ownerScope == null || !current(ownerScope, false)) return List.of();
        QualityRecoveryTaskQueryCriteria raw = new QualityRecoveryTaskQueryCriteria(
                criteria.sourceId(), criteria.status(), criteria.afterOccurredAt(),
                criteria.afterTaskId(), criteria.limit());
        List<QualityRecoveryTask> candidates = safeList(raw);
        if (candidates.size() > criteria.limit()
                || candidates.stream().anyMatch(candidate ->
                        !criteria.sourceId().equals(candidate.sourceId()))) throw unavailable();
        ArrayList<Authorized> visible = new ArrayList<>();
        Generation generation = Generation.from(ownerScope.token());
        for (QualityRecoveryTask candidate : candidates) {
            Binding binding = authorize(candidate, actor, traceId, false);
            if (binding == null || !current(binding, false)) continue;
            Generation candidateGeneration = Generation.from(binding.token());
            if (generation != null && !generation.equals(candidateGeneration)) throw unavailable();
            generation = candidateGeneration;
            visible.add(new Authorized(candidate, binding));
        }
        if (!current(ownerScope, false)) return List.of();
        List<QualityRecoveryTask> facts = visible.stream().map(Authorized::task).toList();
        record(facts, actor, "quality-recovery-task-list-read", traceId);
        return facts.stream().map(QualityRecoveryTaskView::from).toList();
    }

    private Binding authorizeOwnerScope(
            String sourceId,
            QualitySnapshotActorContext actor,
            String traceId) {
        CompositeAuthorizationRequest request = new CompositeAuthorizationRequest(
                actor.authorizationSessionRef(), "DATA_SOURCE", "data-quality.read",
                QualityEligibilityQueryService.digest(sourceId), 1,
                Optional.empty(), Optional.empty(), traceId);
        CompositeAuthorizationDecision decision;
        try {
            decision = authorization.authorize(request);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
        if (decision == null
                || decision.outcome() == CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE) {
            throw unavailable();
        }
        if (decision.outcome() != CompositeAuthorizationOutcome.ALLOW
                || decision.objectVersion() != 1
                || !decision.scopeAnchorSummary().contains("OWNED_SOURCE")) return null;
        if (!REQUIRED_PROJECTION.equals(decision.fieldProjectionSummary())) throw unavailable();
        return new Binding(request, decision.decisionToken());
    }

    public QualityRecoveryTaskView get(
            UUID taskId, QualitySnapshotActorContext actor, String traceId) {
        requireUuidV7(taskId);
        QualityRecoveryTask first = safeFind(taskId).orElseThrow(
                QualityRecoveryTaskQueryService::forbidden);
        Binding binding = authorize(first, actor, traceId, true);
        QualityRecoveryTask refreshed = safeFind(taskId).orElseThrow(
                QualityRecoveryTaskQueryService::forbidden);
        if (!first.equals(refreshed)) throw forbidden();
        Binding current = authorize(refreshed, actor, traceId, true);
        if (!Generation.from(binding.token()).equals(Generation.from(current.token()))) {
            throw unavailable();
        }
        if (!current(current, true)) throw forbidden();
        record(List.of(refreshed), actor, "quality-recovery-task-detail-read", traceId);
        return QualityRecoveryTaskView.from(refreshed);
    }

    private Binding authorize(
            QualityRecoveryTask task,
            QualitySnapshotActorContext actor,
            String traceId,
            boolean conceal) {
        CompositeAuthorizationRequest request = new CompositeAuthorizationRequest(
                actor.authorizationSessionRef(), "RECOVERY_TASK", "data-quality.read",
                QualityEligibilityQueryService.digest(task.taskId().toString()),
                task.aggregateVersion(), Optional.empty(), Optional.empty(), traceId);
        CompositeAuthorizationDecision decision;
        try {
            decision = authorization.authorize(request);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
        if (decision == null
                || decision.outcome() == CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE) {
            throw unavailable();
        }
        if (decision.outcome() != CompositeAuthorizationOutcome.ALLOW
                || decision.objectVersion() != task.aggregateVersion()
                || !decision.scopeAnchorSummary().contains("OWNED_SOURCE")) {
            if (conceal) throw forbidden();
            return null;
        }
        if (!REQUIRED_PROJECTION.equals(decision.fieldProjectionSummary())) throw unavailable();
        return new Binding(request, decision.decisionToken());
    }

    private boolean current(Binding binding, boolean conceal) {
        try {
            var decision = recheck.recheck(new CompositeAuthorizationRecheckRequest(
                    binding.request(), binding.token()));
            if (decision == null
                    || decision.outcome()
                    == CompositeAuthorizationRecheckOutcome.DEPENDENCY_UNAVAILABLE) {
                throw unavailable();
            }
            if (decision.outcome() != CompositeAuthorizationRecheckOutcome.CURRENT) {
                if (conceal) throw forbidden();
                return false;
            }
            return true;
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private List<QualityRecoveryTask> safeList(QualityRecoveryTaskQueryCriteria criteria) {
        try {
            return List.copyOf(tasks.findCurrent(criteria));
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private Optional<QualityRecoveryTask> safeFind(UUID taskId) {
        try {
            return tasks.findCurrentById(taskId);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private void record(
            List<QualityRecoveryTask> facts,
            QualitySnapshotActorContext actor,
            String action,
            String traceId) {
        if (facts.isEmpty()) return;
        try {
            audit.record(facts, actor, action, traceId);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7 || value.variant() != 2) throw forbidden();
    }

    private static IngestionQualityApplicationException forbidden() {
        return new IngestionQualityApplicationException("INGESTION_QUALITY_FORBIDDEN");
    }

    private static IngestionQualityApplicationException unavailable() {
        return new IngestionQualityApplicationException(
                "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
    }

    private record Binding(
            CompositeAuthorizationRequest request,
            CompositeAuthorizationDecisionToken token) {}
    private record Authorized(QualityRecoveryTask task, Binding binding) {}
    private record Generation(
            long identity, long relation, long grant, long invalidation,
            long policy, String policyVersion) {
        private static Generation from(CompositeAuthorizationDecisionToken token) {
            return new Generation(
                    token.identityVersion(), token.relationVersion(), token.grantVersion(),
                    token.invalidationVersion(), token.policySequence(), token.policyVersion());
        }
    }
}
