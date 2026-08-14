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
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibility;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** All-member owner authorization and audit-before-response boundary for eligibility facts. */
public final class QualityEligibilityQueryService {
    private static final int MINIMUM_RAW_PAGE_SIZE = 21;
    private static final int MAXIMUM_CANDIDATES_PER_REQUEST = 101;
    private static final Map<String, FieldVisibility> REQUIRED_PROJECTION = Map.of(
            "B", FieldVisibility.CLEAR,
            "I", FieldVisibility.MASKED,
            "C", FieldVisibility.HIDDEN,
            "S", FieldVisibility.HIDDEN,
            "E", FieldVisibility.CLEAR,
            "N", FieldVisibility.HIDDEN,
            "G", FieldVisibility.CLEAR,
            "T", FieldVisibility.CLEAR);

    private final QualityEligibilityQueryPort eligibilities;
    private final CompositeAuthorizationPort authorization;
    private final CompositeAuthorizationRecheckPort recheck;
    private final QualityEligibilityReadAuditPort readAudit;

    public QualityEligibilityQueryService(
            QualityEligibilityQueryPort eligibilities,
            CompositeAuthorizationPort authorization,
            CompositeAuthorizationRecheckPort recheck,
            QualityEligibilityReadAuditPort readAudit) {
        this.eligibilities = java.util.Objects.requireNonNull(eligibilities);
        this.authorization = java.util.Objects.requireNonNull(authorization);
        this.recheck = java.util.Objects.requireNonNull(recheck);
        this.readAudit = java.util.Objects.requireNonNull(readAudit);
    }

    public List<QualityEligibilityView> list(
            QualityEligibilityQueryCriteria criteria,
            QualitySnapshotActorContext actor,
            String traceId) {
        java.util.Objects.requireNonNull(criteria);
        ArrayList<AuthorizedEligibility> visible = new ArrayList<>();
        java.time.Instant afterOccurredAt = criteria.afterOccurredAt();
        UUID afterEligibilityId = criteria.afterEligibilityId();
        int examined = 0;
        boolean exhausted = false;
        while (true) {
            while (visible.size() < criteria.limit()
                    && !exhausted
                    && examined < MAXIMUM_CANDIDATES_PER_REQUEST) {
                int pageLimit = Math.min(
                        MAXIMUM_CANDIDATES_PER_REQUEST - examined,
                        Math.max(criteria.limit() - visible.size(), MINIMUM_RAW_PAGE_SIZE));
                QualityEligibilityQueryCriteria rawPage = criteriaWithCursor(
                        criteria, afterOccurredAt, afterEligibilityId, pageLimit);
                List<QualityEligibility> candidates = safeCandidates(rawPage);
                if (candidates.size() > pageLimit) throw unavailable();
                if (candidates.isEmpty()) {
                    exhausted = true;
                    break;
                }
                int consumed = 0;
                for (QualityEligibility candidate : candidates) {
                    examined++;
                    consumed++;
                    afterOccurredAt = candidate.occurredAt();
                    afterEligibilityId = candidate.eligibilityId();
                    AuthorizationBinding binding = authorizeAll(
                            candidate, actor, traceId, false);
                    if (binding != null) {
                        visible.add(new AuthorizedEligibility(candidate, binding));
                        if (visible.size() == criteria.limit()) break;
                    }
                    if (examined == MAXIMUM_CANDIDATES_PER_REQUEST) break;
                }
                if (consumed == candidates.size() && candidates.size() < pageLimit) {
                    exhausted = true;
                }
            }

            requireSingleGeneration(visible.stream()
                    .map(AuthorizedEligibility::binding).toList());
            boolean stale = false;
            for (var iterator = visible.iterator(); iterator.hasNext();) {
                if (!recheckAll(iterator.next().binding(), false)) {
                    iterator.remove();
                    stale = true;
                }
            }
            if (!visible.isEmpty()
                    && !recheckOne(visible.getFirst().binding().members().getFirst(), false)) {
                visible.clear();
                stale = true;
                afterOccurredAt = criteria.afterOccurredAt();
                afterEligibilityId = criteria.afterEligibilityId();
                exhausted = false;
            }
            if (!stale) break;
            if (exhausted) break;
            if (examined >= MAXIMUM_CANDIDATES_PER_REQUEST) throw unavailable();
        }
        List<QualityEligibility> facts = visible.stream()
                .map(AuthorizedEligibility::eligibility).toList();
        audit(facts, actor, "quality-eligibility-list-read", traceId);
        return facts.stream().map(QualityEligibilityView::from).toList();
    }

    public QualityEligibilityView get(
            UUID eligibilityId, QualitySnapshotActorContext actor, String traceId) {
        requireUuidV7(eligibilityId);
        QualityEligibility candidate = safeFind(eligibilityId).orElseThrow(
                QualityEligibilityQueryService::forbidden);
        AuthorizationBinding initial = authorizeAll(candidate, actor, traceId, true);
        QualityEligibility refreshed = safeFind(eligibilityId).orElseThrow(
                QualityEligibilityQueryService::forbidden);
        if (!initial.matches(refreshed)) throw forbidden();
        AuthorizationBinding current = authorizeAll(refreshed, actor, traceId, true);
        if (!initial.generation().equals(current.generation())) throw unavailable();
        if (!recheckAll(current, true)
                || !recheckOne(current.members().getFirst(), true)) throw forbidden();
        audit(List.of(refreshed), actor, "quality-eligibility-detail-read", traceId);
        return QualityEligibilityView.from(refreshed);
    }

    private AuthorizationBinding authorizeAll(
            QualityEligibility candidate,
            QualitySnapshotActorContext actor,
            String traceId,
            boolean concealDeny) {
        ArrayList<MemberAuthorization> members = new ArrayList<>();
        AuthorizationGeneration generation = null;
        for (var member : candidate.members()) {
            CompositeAuthorizationRequest request = new CompositeAuthorizationRequest(
                    actor.authorizationSessionRef(), "DEPENDENCY", "data-quality.read",
                    digest(member.dependencyId()), member.dependencyVersion(),
                    Optional.empty(), Optional.empty(), traceId);
            CompositeAuthorizationDecision decision;
            try {
                decision = authorization.authorize(request);
            } catch (RuntimeException unavailable) {
                throw unavailable();
            }
            if (decision == null
                    || decision.outcome() == CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE) {
                throw unavailable();
            }
            if (decision.outcome() != CompositeAuthorizationOutcome.ALLOW
                    || decision.objectVersion() != member.dependencyVersion()
                    || !decision.scopeAnchorSummary().contains("OWNED_SOURCE")) {
                if (concealDeny) throw forbidden();
                return null;
            }
            if (!decision.fieldProjectionSummary().equals(REQUIRED_PROJECTION)) {
                throw unavailable();
            }
            AuthorizationGeneration memberGeneration = AuthorizationGeneration.from(
                    decision.decisionToken());
            if (generation != null && !generation.equals(memberGeneration)) throw unavailable();
            generation = memberGeneration;
            members.add(new MemberAuthorization(request, decision.decisionToken()));
        }
        return new AuthorizationBinding(
                candidate.eligibilityId(), candidate.aggregateVersion(),
                candidate.businessKey(), memberSetDigest(candidate), generation, members);
    }

    private boolean recheckAll(AuthorizationBinding binding, boolean concealDeny) {
        for (MemberAuthorization member : binding.members()) {
            if (!recheckOne(member, concealDeny)) return false;
        }
        return true;
    }

    private boolean recheckOne(MemberAuthorization member, boolean concealDeny) {
        try {
            var current = recheck.recheck(new CompositeAuthorizationRecheckRequest(
                    member.request(), member.decisionToken()));
            if (current == null
                    || current.outcome()
                            == CompositeAuthorizationRecheckOutcome.DEPENDENCY_UNAVAILABLE) {
                throw unavailable();
            }
            if (current.outcome() != CompositeAuthorizationRecheckOutcome.CURRENT) {
                if (concealDeny) throw forbidden();
                return false;
            }
            return true;
        } catch (IngestionQualityApplicationException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
    }

    private static void requireSingleGeneration(List<AuthorizationBinding> bindings) {
        if (bindings.isEmpty()) return;
        AuthorizationGeneration generation = bindings.getFirst().generation();
        if (bindings.stream().anyMatch(binding -> !generation.equals(binding.generation()))) {
            throw unavailable();
        }
    }

    private static String memberSetDigest(QualityEligibility candidate) {
        String material = candidate.members().stream().map(member -> String.join("\u001f",
                member.sourceId(), Long.toString(member.sourceVersion()),
                member.dependencyId(), Long.toString(member.dependencyVersion())))
                .collect(java.util.stream.Collectors.joining("\u001e"));
        return digest(material);
    }

    private void audit(
            List<QualityEligibility> values,
            QualitySnapshotActorContext actor,
            String action,
            String traceId) {
        if (values.isEmpty()) return;
        try {
            readAudit.record(List.copyOf(values), actor, action, traceId);
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
    }

    private List<QualityEligibility> safeCandidates(QualityEligibilityQueryCriteria criteria) {
        try {
            return List.copyOf(java.util.Objects.requireNonNull(
                    eligibilities.findCurrent(criteria)));
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
    }

    private Optional<QualityEligibility> safeFind(UUID id) {
        try {
            return java.util.Objects.requireNonNull(eligibilities.findCurrentById(id));
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
    }

    private static QualityEligibilityQueryCriteria criteriaWithCursor(
            QualityEligibilityQueryCriteria base,
            java.time.Instant occurredAt,
            UUID eligibilityId,
            int limit) {
        return new QualityEligibilityQueryCriteria(
                base.status(), base.ruleId(), occurredAt, eligibilityId, limit);
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
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

    private record AuthorizedEligibility(
            QualityEligibility eligibility, AuthorizationBinding binding) {}

    private record AuthorizationBinding(
            UUID eligibilityId,
            long aggregateVersion,
            String businessKey,
            String memberSetDigest,
            AuthorizationGeneration generation,
            List<MemberAuthorization> members) {
        private AuthorizationBinding {
            java.util.Objects.requireNonNull(eligibilityId);
            java.util.Objects.requireNonNull(businessKey);
            java.util.Objects.requireNonNull(memberSetDigest);
            java.util.Objects.requireNonNull(generation);
            members = List.copyOf(members);
            if (members.isEmpty()) throw unavailable();
        }

        private boolean matches(QualityEligibility candidate) {
            return eligibilityId.equals(candidate.eligibilityId())
                    && aggregateVersion == candidate.aggregateVersion()
                    && businessKey.equals(candidate.businessKey())
                    && memberSetDigest.equals(QualityEligibilityQueryService.memberSetDigest(
                            candidate));
        }
    }

    private record MemberAuthorization(
            CompositeAuthorizationRequest request,
            CompositeAuthorizationDecisionToken decisionToken) {}

    private record AuthorizationGeneration(
            long identityVersion,
            long relationVersion,
            long grantVersion,
            long invalidationVersion,
            long policySequence,
            String policyVersion) {
        private static AuthorizationGeneration from(
                CompositeAuthorizationDecisionToken token) {
            return new AuthorizationGeneration(
                    token.identityVersion(), token.relationVersion(), token.grantVersion(),
                    token.invalidationVersion(), token.policySequence(), token.policyVersion());
        }
    }
}
