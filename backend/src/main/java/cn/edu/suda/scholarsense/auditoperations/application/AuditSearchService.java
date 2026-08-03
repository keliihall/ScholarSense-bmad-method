package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchCriteria;
import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchView;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionObjectClass;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionObjectEvidence;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionPort;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionRequest;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionSafeDocument;
import cn.edu.suda.scholarsense.identityaccess.api.FieldProjectionValueReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Authorizes every request, freezes a complete snapshot, projects fields, then audits before return. */
public final class AuditSearchService {
    private final AuditSearchQueryPort queries;
    private final AuditSearchAuthorizationGateway authorization;
    private final AuditSearchTokenGateway tokenization;
    private final SearchAuditPort audit;
    private final AuditClock clock;
    private final AuditRequesterResolver requester;
    private final FieldProjectionPort fieldProjection;
    private final String projectionKeyStateVersion;

    public AuditSearchService(
            AuditSearchQueryPort queries,
            AuditSearchAuthorizationGateway authorization,
            AuditSearchTokenGateway tokenization,
            SearchAuditPort audit,
            AuditClock clock,
            AuditRequesterResolver requester,
            FieldProjectionPort fieldProjection,
            String projectionKeyStateVersion) {
        this.queries = Objects.requireNonNull(queries);
        this.authorization = Objects.requireNonNull(authorization);
        this.tokenization = Objects.requireNonNull(tokenization);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
        this.requester = Objects.requireNonNull(requester);
        this.fieldProjection = Objects.requireNonNull(fieldProjection);
        if (projectionKeyStateVersion == null
                || !projectionKeyStateVersion.matches("[a-z0-9][a-z0-9._-]{2,63}")) {
            throw new IllegalArgumentException("AUDIT_SEARCH_KEY_STATE_INVALID");
        }
        this.projectionKeyStateVersion = projectionKeyStateVersion;
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
                    "audit-domain",
                    criteria.requestTraceId()));
        } catch (RuntimeException unavailable) {
            return reject(criteria, "AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE", null);
        }
        if (!decision.allowed()) {
            return reject(criteria, "AUDIT_SEARCH_FORBIDDEN", null);
        }
        if (!AuditSearchDecisionValidator.isContextValid(criteria.view(), decision)) {
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
        List<ProjectedAuditRecord> items;
        try {
            items = project(slice.rows(), criteria, session, decision.action());
        } catch (RuntimeException projectionFailure) {
            return reject(criteria, "AUDIT_SEARCH_DEPENDENCY_UNAVAILABLE", asOf);
        }
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

    private List<ProjectedAuditRecord> project(
            List<AuditSearchRow> rows,
            AuditSearchCriteria criteria,
            String session,
            String action) {
        List<ProjectedAuditRecord> result = new ArrayList<>();
        for (AuditSearchRow row : rows) {
            CompositeAuthorizationRequest authorization = new CompositeAuthorizationRequest(
                    session,
                    criteria.view() == AuditSearchView.BUSINESS ? "AGGREGATE_REPORT" : "TELEMETRY",
                    action,
                    digest(row.recordId().toString()),
                    row.ledgerSequence(),
                    Optional.empty(),
                    Optional.empty(),
                    digest(criteria.requestTraceId()).substring(0, 32));
            var projection = fieldProjection.project(new FieldProjectionRequest(
                    authorization,
                    FieldProjectionObjectClass.AUDIT_SEARCH_RECORD,
                    new FieldProjectionObjectEvidence(
                            action,
                            clock.now(),
                            Optional.empty(),
                            true,
                            false,
                            false,
                            Set.of(),
                            Optional.empty(),
                            projectionKeyStateVersion),
                    valueReferences(row)));
            if (!projection.allowed()) {
                throw new IllegalStateException("AUDIT_SEARCH_FIELD_PROJECTION_REJECTED");
            }
            result.add(new ProjectedAuditRecord(FieldProjectionSafeDocument.from(projection).jsonValues()));
        }
        return List.copyOf(result);
    }

    private static List<FieldProjectionValueReference> valueReferences(AuditSearchRow row) {
        List<FieldProjectionValueReference> values = new ArrayList<>();
        values.add(value("recordId", "B", "string", () -> row.recordId().toString()));
        values.add(value("ledgerSequence", "B", "integer", row::ledgerSequence));
        values.add(value("occurredAt", "B", "timestamp", row::occurredAt));
        add(values, "outcome", "B", "string", row.outcome());
        add(values, "factSchemaVersion", "B", "string", row.factSchemaVersion());
        add(values, "policyVersion", "B", "string", row.policyVersion());
        add(values, "retentionScheduleVersion", "B", "string", row.retentionScheduleVersion());
        add(values, "actorDisplayRef", "I", "string", row.actorSearchToken());
        add(values, "objectDisplayRef", "I", "string", row.objectSearchToken());
        add(values, "businessActionCategory", "G", "string", row.businessActionCategory());
        add(values, "businessObjectCategory", "G", "string", row.businessObjectCategory());
        add(values, "rolePackageSummary", "G", "string", row.rolePackageSummary());
        add(values, "projectionScope", "G", "string", row.projectionScope());
        add(values, "producerModule", "T", "string", row.producerModule());
        add(values, "eventType", "T", "string", row.eventType());
        add(values, "reasonCode", "T", "string", row.reasonCode());
        add(values, "traceId", "T", "string", row.traceId());
        values.add(value("integrityStatus", "T", "string", () -> "verified"));
        values.add(value("archiveStatus", "T", "string", () -> "online"));
        values.add(value("projectionStatus", "T", "string", () -> "current"));
        values.add(value("sourceNetworkRecorded", "T", "boolean", row::sourceNetworkRecorded));
        return List.copyOf(values);
    }

    private static void add(
            List<FieldProjectionValueReference> values,
            String name,
            String fieldClass,
            String valueType,
            Object value) {
        if (value != null) {
            values.add(value(name, fieldClass, valueType, () -> value));
        }
    }

    private static FieldProjectionValueReference value(
            String name,
            String fieldClass,
            String valueType,
            cn.edu.suda.scholarsense.identityaccess.api.ServerOwnedFieldValueReference value) {
        return FieldProjectionValueReference.serverOwned(name, fieldClass, valueType, value);
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
