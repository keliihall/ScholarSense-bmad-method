package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Applies the same request-time R3/R7 decision and audit-before-return rule to execution evidence. */
public final class RetentionExecutionReadService {
    private final RetentionExecutionQueryPort executions;
    private final AuditSearchAuthorizationGateway authorization;
    private final SearchAuditPort audit;
    private final AuditClock clock;

    public RetentionExecutionReadService(
            RetentionExecutionQueryPort executions,
            AuditSearchAuthorizationGateway authorization,
            SearchAuditPort audit,
            AuditClock clock) {
        this.executions = Objects.requireNonNull(executions);
        this.authorization = Objects.requireNonNull(authorization);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public RetentionExecutionEvidenceView read(
            UUID executionId, AuditSearchView view, String sessionPseudonym, String traceId) {
        if (executionId == null || executionId.version() != 7 || executionId.variant() != 2) {
            return reject(sessionPseudonym, view, traceId);
        }
        AuthorizedAuditSearchDecision decision;
        try {
            decision = authorization.authorize(new AuthorizedAuditSearchRequest(
                    sessionPseudonym, view, "retention-execution", "audit-domain", traceId));
        } catch (RuntimeException unavailable) {
            commit(sessionPseudonym, action(view), "rejected", "AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE", traceId);
            throw new AuditSearchException("AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE");
        }
        if (!decision.allowed()) {
            return reject(sessionPseudonym, view, traceId);
        }
        if (!AuditSearchDecisionValidator.isValid(view, decision)) {
            commit(sessionPseudonym, action(view), "rejected", "AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE", traceId);
            throw new AuditSearchException("AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE");
        }
        RetentionExecution execution;
        try {
            execution = executions.find(executionId).orElse(null);
        } catch (RuntimeException unavailable) {
            commit(sessionPseudonym, decision.action(), "rejected", "AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE", traceId);
            throw new AuditSearchException("AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE");
        }
        if (execution == null) return reject(sessionPseudonym, view, traceId);
        RetentionExecutionEvidenceView result = project(execution, decision.fieldProjection());
        commit(sessionPseudonym, decision.action(), "accepted", null, traceId);
        return result;
    }

    private RetentionExecutionEvidenceView reject(String session, AuditSearchView view, String traceId) {
        commit(session, action(view), "rejected", "AUDIT_EVIDENCE_NOT_AVAILABLE", traceId);
        throw new AuditSearchException("AUDIT_EVIDENCE_NOT_AVAILABLE");
    }

    private void commit(String session, String action, String outcome, String error, String traceId) {
        try {
            audit.commit(new SearchAuditEvent(session, action, outcome, error,
                    List.of("executionId", "view"), digest("executionId\nview"), null, traceId, clock.now()));
        } catch (RuntimeException failure) {
            throw new AuditSearchException("AUDIT_SEARCH_AUDIT_COMMIT_FAILED");
        }
    }

    private static RetentionExecutionEvidenceView project(
            RetentionExecution execution, Map<String, AuditFieldVisibility> projection) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("executionId", execution.executionId());
        fields.put("scheduleVersion", execution.scheduleVersion());
        fields.put("state", execution.state().name().toLowerCase(java.util.Locale.ROOT));
        fields.put("nonProductionEvidence", execution.nonProductionEvidence());
        AuditFieldVisibility identity = projection.getOrDefault("I", AuditFieldVisibility.HIDDEN);
        if (identity == AuditFieldVisibility.MASKED) fields.put("actorDisplayRef", "actor-00000001");
        AuditFieldVisibility category = projection.getOrDefault("G", AuditFieldVisibility.HIDDEN);
        if (category == AuditFieldVisibility.CLEAR) {
            fields.put("scopeType", execution.scopeType());
            fields.put("fixtureId", execution.fixtureId());
            fields.put("action", execution.action());
        } else if (category == AuditFieldVisibility.MASKED) {
            fields.put("scopeType", "scope-masked");
            fields.put("fixtureId", "fixture-masked");
            fields.put("action", "action-masked");
        }
        AuditFieldVisibility technical = projection.getOrDefault("T", AuditFieldVisibility.HIDDEN);
        if (technical == AuditFieldVisibility.CLEAR) {
            fields.put("asOfSequence", execution.asOfSequence());
            fields.put("sourceLedgerHead", execution.sourceLedgerHead());
            fields.put("projectionWatermark", execution.projectionWatermark());
            if (execution.archiveDigest() != null) fields.put("archiveDigest", execution.archiveDigest());
            fields.put("trustedAt", execution.trustedAt());
            fields.put("traceId", execution.traceId());
            fields.put("unmetGuards", execution.unmetGuards());
            fields.put("steps", execution.steps());
        } else if (technical == AuditFieldVisibility.MASKED) {
            fields.put("archiveDigest", "digest-masked");
            fields.put("unmetGuards", execution.unmetGuards().isEmpty() ? List.of() : List.of("guard-masked"));
        }
        return new RetentionExecutionEvidenceView(fields);
    }

    private static String action(AuditSearchView view) {
        return view == AuditSearchView.BUSINESS
                ? "audit.search-business-metadata" : "audit.search-technical-metadata";
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
