package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchCriteria;
import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Authorizes every request, freezes a complete snapshot, projects fields, then audits before return. */
public final class AuditSearchService {
    private final AuditSearchQueryPort queries;
    private final AuditSearchAuthorizationGateway authorization;
    private final AuditSearchTokenGateway tokenization;
    private final SearchAuditPort audit;
    private final AuditClock clock;
    private final AuditRequesterResolver requester;

    public AuditSearchService(
            AuditSearchQueryPort queries,
            AuditSearchAuthorizationGateway authorization,
            AuditSearchTokenGateway tokenization,
            SearchAuditPort audit,
            AuditClock clock,
            AuditRequesterResolver requester) {
        this.queries = Objects.requireNonNull(queries);
        this.authorization = Objects.requireNonNull(authorization);
        this.tokenization = Objects.requireNonNull(tokenization);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
        this.requester = Objects.requireNonNull(requester);
    }

    public AuditSearchPage search(AuditSearchCriteria criteria) {
        Objects.requireNonNull(criteria);
        String session = requester.currentSessionPseudonym(criteria.requesterKey());
        AuthorizedAuditSearchDecision decision;
        try {
            decision = authorization.authorize(new AuthorizedAuditSearchRequest(
                    session,
                    criteria.view(),
                    criteria.objectType() == null ? "audit-record" : criteria.objectType(),
                    criteria.objectType() == null ? "audit-domain" : criteria.objectType(),
                    criteria.requestTraceId()));
        } catch (RuntimeException unavailable) {
            return reject(criteria, "AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE", null);
        }
        if (!decision.allowed()) {
            return reject(criteria, "AUDIT_SEARCH_FORBIDDEN", null);
        }
        if (!AuditSearchDecisionValidator.isValid(criteria.view(), decision)) {
            return reject(criteria, "AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE", null);
        }

        AuditSearchSnapshot snapshot;
        try {
            snapshot = queries.snapshot();
        } catch (RuntimeException unavailable) {
            return reject(criteria, "AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE", null);
        }
        long asOf = criteria.asOfSequence() == null
                ? Math.min(snapshot.sourceLedgerHead(), snapshot.projectionWatermark())
                : criteria.asOfSequence();
        if (asOf > snapshot.projectionWatermark() || asOf > snapshot.sourceLedgerHead()) {
            return reject(criteria, "AUDIT_SEARCH_PROJECTION_NOT_CAUGHT_UP", asOf);
        }

        List<String> actorTokens;
        List<String> objectTokens;
        try {
            actorTokens = tokens(criteria.actorRef(), AuditTokenQueryDomain.ACTOR);
            objectTokens = tokens(criteria.objectRef(), AuditTokenQueryDomain.OBJECT);
        } catch (RuntimeException unavailable) {
            return reject(criteria, "AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE", asOf);
        }
        AuditSearchResultSlice slice;
        try {
            slice = queries.search(criteria, actorTokens, objectTokens, asOf);
        } catch (RuntimeException unavailable) {
            return reject(criteria, "AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE", asOf);
        }
        List<ProjectedAuditRecord> items = project(slice.rows(), decision);
        commitAudit(criteria, decision.action(), "accepted", null, asOf);
        return new AuditSearchPage(
                items, criteria.page(), criteria.size(), slice.total(), asOf,
                snapshot.sourceLedgerHead(), snapshot.projectionWatermark(), snapshot.dataCutoffAt(),
                "RS-1.0.0", decision.rfpVersion(),
                snapshot.projectionWatermark() < snapshot.sourceLedgerHead() ? "degraded" : "current");
    }

    private List<String> tokens(String rawReference, AuditTokenQueryDomain domain) {
        if (rawReference == null) return List.of();
        try {
            Instant retainedFrom = clock.now().atZone(ZoneOffset.UTC).minusYears(3).toInstant();
            List<AuditTokenQueryValue> values = tokenization.query(
                    new AuditTokenQueryRequest(domain, rawReference, retainedFrom));
            String prefix = domain == AuditTokenQueryDomain.ACTOR ? "ast_v1_" : "ost_v1_";
            if (values.isEmpty()
                    || values.stream().anyMatch(value -> !"AUDIT-TOKENIZATION-1.0.0".equals(value.profileVersion())
                            || value.keyVersion() == null || value.keyVersion().isBlank()
                            || value.value() == null || !value.value().startsWith(prefix))
                    || values.stream().map(AuditTokenQueryValue::keyVersion).distinct().count() != values.size()) {
                throw new IllegalStateException("AUDIT_SEARCH_TOKEN_PROFILE_DRIFT");
            }
            return values.stream().map(AuditTokenQueryValue::value).toList();
        } catch (RuntimeException unavailable) {
            throw new IllegalStateException("AUDIT_SEARCH_TOKENIZATION_UNAVAILABLE", unavailable);
        }
    }

    private AuditSearchPage reject(AuditSearchCriteria criteria, String code, Long asOf) {
        commitAudit(criteria, action(criteria.view()), "rejected", code, asOf);
        throw new AuditSearchException(code);
    }

    private void commitAudit(
            AuditSearchCriteria criteria,
            String action,
            String outcome,
            String code,
            Long asOf) {
        List<String> types = filterTypes(criteria);
        try {
            audit.commit(new SearchAuditEvent(
                    criteria.requesterKey(), action, outcome, code, types,
                    digest(String.join("\n", types)), asOf, criteria.requestTraceId(), clock.now()));
        } catch (RuntimeException failed) {
            throw new AuditSearchException("AUDIT_SEARCH_AUDIT_COMMIT_FAILED");
        }
    }

    private static List<ProjectedAuditRecord> project(
            List<AuditSearchRow> rows, AuthorizedAuditSearchDecision decision) {
        Map<String, String> actorAliases = new LinkedHashMap<>();
        Map<String, String> objectAliases = new LinkedHashMap<>();
        List<ProjectedAuditRecord> result = new ArrayList<>();
        for (AuditSearchRow row : rows) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("recordId", row.recordId());
            fields.put("ledgerSequence", row.ledgerSequence());
            fields.put("occurredAt", row.occurredAt());
            fields.put("outcome", row.outcome());
            fields.put("factSchemaVersion", row.factSchemaVersion());
            fields.put("policyVersion", row.policyVersion());
            fields.put("retentionScheduleVersion", row.retentionScheduleVersion());
            if (decision.fieldProjection().get("I") == AuditFieldVisibility.MASKED) {
                alias(fields, "actorDisplayRef", "actor", row.actorSearchToken(), actorAliases);
                alias(fields, "objectDisplayRef", "object", row.objectSearchToken(), objectAliases);
            }
            putCategoryFields(fields, row, decision.fieldProjection().get("G"));
            putTechnicalFields(fields, row, decision.fieldProjection().get("T"));
            result.add(new ProjectedAuditRecord(fields));
        }
        return List.copyOf(result);
    }

    private static void alias(
            Map<String, Object> fields, String field, String prefix, String token, Map<String, String> aliases) {
        if (token != null) {
            fields.put(field, aliases.computeIfAbsent(
                    token, ignored -> "%s-%08X".formatted(prefix, aliases.size() + 1)));
        }
    }

    private static void putCategoryFields(
            Map<String, Object> fields, AuditSearchRow row, AuditFieldVisibility visibility) {
        if (visibility == AuditFieldVisibility.CLEAR) {
            put(fields, "businessActionCategory", row.businessActionCategory());
            put(fields, "businessObjectCategory", row.businessObjectCategory());
            put(fields, "rolePackageSummary", row.rolePackageSummary());
            put(fields, "projectionScope", row.projectionScope());
        } else if (visibility == AuditFieldVisibility.MASKED) {
            fields.put("businessActionCategory", "category-masked");
            fields.put("businessObjectCategory", "category-masked");
            fields.put("rolePackageSummary", "role-package-masked");
            fields.put("projectionScope", "scope-masked");
        }
    }

    private static void putTechnicalFields(
            Map<String, Object> fields, AuditSearchRow row, AuditFieldVisibility visibility) {
        if (visibility == AuditFieldVisibility.CLEAR) {
            put(fields, "producerModule", row.producerModule());
            put(fields, "eventType", row.eventType());
            put(fields, "reasonCode", row.reasonCode());
            put(fields, "traceId", row.traceId());
            fields.put("integrityStatus", "verified");
            fields.put("archiveStatus", "online");
            fields.put("projectionStatus", "current");
            fields.put("sourceNetworkRecorded", row.sourceNetworkRecorded());
        } else if (visibility == AuditFieldVisibility.MASKED) {
            fields.put("producerModule", "module-masked");
            fields.put("eventType", "event-masked");
            fields.put("reasonCode", "REASON_MASKED");
            fields.put("traceId", "trace-masked");
            fields.put("integrityStatus", "verified");
            fields.put("archiveStatus", "online");
            fields.put("projectionStatus", "current");
            fields.put("sourceNetworkRecorded", row.sourceNetworkRecorded());
        }
    }

    private static void put(Map<String, Object> fields, String key, Object value) {
        if (value != null) fields.put(key, value);
    }

    private static List<String> filterTypes(AuditSearchCriteria criteria) {
        List<String> types = new ArrayList<>(List.of("page", "size", "view"));
        if (criteria.actorRef() != null) types.add("actor");
        if (criteria.objectType() != null) types.add("objectType");
        if (criteria.objectRef() != null) types.add("object");
        if (criteria.action() != null) types.add("action");
        if (criteria.occurredFrom() != null) types.add("occurredFrom");
        if (criteria.occurredTo() != null) types.add("occurredTo");
        if (criteria.outcome() != null) types.add("outcome");
        if (criteria.traceId() != null) types.add("traceId");
        if (criteria.asOfSequence() != null) types.add("asOfSequence");
        return types.stream().sorted().toList();
    }

    private static String action(AuditSearchView view) {
        return view == AuditSearchView.BUSINESS
                ? "audit.search-business-metadata" : "audit.search-technical-metadata";
    }

    private static String digest(String material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
