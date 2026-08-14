package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecisionToken;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.FieldVisibility;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyRequirement;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibility;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityMemberEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityReason;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleVersionIdentity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QualityEligibilityQueryServiceTest {
    private static final UUID ELIGIBILITY_ID =
            UUID.fromString("019fe570-0000-7000-8000-000000000701");
    private static final String TRACE_ID = "00112233445566778899aabbccddeeff";
    private static final QualitySnapshotActorContext ACTOR =
            new QualitySnapshotActorContext("session-ref", "actor-ref", "127.0.0.1");

    @Test
    void requiresEveryMemberAuthorizationAndAuditsTheExactReturnedProjection() {
        QualityEligibility fact = eligibility();
        List<String> authorizedDependencies = new ArrayList<>();
        List<UUID> audited = new ArrayList<>();
        var service = new QualityEligibilityQueryService(
                new Store(fact), request -> {
                    authorizedDependencies.add(request.objectTokenDigest());
                    return allow(request.expectedObjectVersion());
                }, ignored -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT"),
                (facts, actor, action, traceId) -> facts.forEach(item -> audited.add(item.eligibilityId())));

        QualityEligibilityView view = service.get(ELIGIBILITY_ID, ACTOR, TRACE_ID);

        assertEquals(ELIGIBILITY_ID, view.eligibilityId());
        assertEquals("fused", view.status());
        assertEquals(4, authorizedDependencies.size());
        assertEquals(List.of(ELIGIBILITY_ID), audited);
    }

    @Test
    void oneDeniedMemberConcealsTheWholeCompositionAndNeverAudits() {
        QualityEligibility fact = eligibility();
        List<UUID> audited = new ArrayList<>();
        var service = new QualityEligibilityQueryService(
                new Store(fact), request -> request.objectTokenDigest().equals(
                        QualityEligibilityQueryService.digest("DEP-P0-DORM-ACCESS-001"))
                        ? deny(request.expectedObjectVersion()) : allow(request.expectedObjectVersion()),
                ignored -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT"),
                (facts, actor, action, traceId) -> facts.forEach(item -> audited.add(item.eligibilityId())));

        assertEquals("INGESTION_QUALITY_FORBIDDEN", code(() ->
                service.get(ELIGIBILITY_ID, ACTOR, TRACE_ID)));
        assertEquals(List.of(), audited);
    }

    @Test
    void listUsesBulkHydrationAndOmitsPartiallyOwnedRowsWithoutCountLeak() {
        QualityEligibility fact = eligibility();
        Store store = new Store(fact);
        var service = new QualityEligibilityQueryService(
                store, request -> deny(request.expectedObjectVersion()),
                ignored -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT"),
                (facts, actor, action, traceId) -> {});

        List<QualityEligibilityView> visible = service.list(
                new QualityEligibilityQueryCriteria(null, null, null, null, 21), ACTOR, TRACE_ID);

        assertEquals(List.of(), visible);
        assertEquals(0, store.detailReads);
    }

    @Test
    void listOmitsStaleRecheckAndContinuesBoundedBackfill() {
        QualityEligibility first = eligibility(
                ELIGIBILITY_ID, "ACC-SAFE-001", Instant.parse("2026-08-10T00:00:02Z"));
        UUID secondId = UUID.fromString("019fe570-0000-7000-8000-000000000702");
        QualityEligibility second = eligibility(
                secondId, "ACC-SAFE-002", Instant.parse("2026-08-10T00:00:01Z"));
        int[] recheckCalls = {0};
        var service = new QualityEligibilityQueryService(
                new PagingStore(List.of(first, second)),
                request -> allow(request.expectedObjectVersion()),
                ignored -> new CompositeAuthorizationRecheckDecision(
                        ++recheckCalls[0] == 1
                                ? CompositeAuthorizationRecheckOutcome.STALE
                                : CompositeAuthorizationRecheckOutcome.CURRENT,
                        "CURRENT"),
                (facts, actor, action, traceId) -> {});

        List<QualityEligibilityView> visible = service.list(
                new QualityEligibilityQueryCriteria(null, null, null, null, 1),
                ACTOR, TRACE_ID);

        assertEquals(List.of(secondId), visible.stream()
                .map(QualityEligibilityView::eligibilityId).toList());
    }

    @Test
    void rejectsAnEligibilityWhoseMemberDecisionsSpanIdentityGenerations() {
        QualityEligibility fact = eligibility();
        int[] authorizationCalls = {0};
        var service = new QualityEligibilityQueryService(
                new Store(fact), request -> allow(
                        request.expectedObjectVersion(), ++authorizationCalls[0]),
                ignored -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT"),
                (facts, actor, action, traceId) -> {});

        assertEquals("INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", code(() ->
                service.get(ELIGIBILITY_ID, ACTOR, TRACE_ID)));
    }

    @Test
    void refreshCannotSubstituteDifferentMemberVersionsUnderTheSameEligibilityId() {
        QualityEligibility initial = eligibility();
        QualityEligibility substituted = eligibility(List.of(
                member("SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001",
                        QualityEligibilityStatus.ELIGIBLE, 711, 2),
                member("SRC-P0-DORM-ACCESS-001", "DEP-P0-DORM-ACCESS-001",
                        QualityEligibilityStatus.FUSED, 712)));
        var service = new QualityEligibilityQueryService(
                new Store(initial, substituted),
                request -> allow(request.expectedObjectVersion()),
                ignored -> new CompositeAuthorizationRecheckDecision(
                        CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT"),
                (facts, actor, action, traceId) -> {});

        assertEquals("INGESTION_QUALITY_FORBIDDEN", code(() ->
                service.get(ELIGIBILITY_ID, ACTOR, TRACE_ID)));
    }

    @Test
    void sealsTheCompleteMemberRecheckWithTheSharedIdentityGeneration() {
        List<String> rechecked = new ArrayList<>();
        var service = new QualityEligibilityQueryService(
                new Store(eligibility()),
                request -> allow(request.expectedObjectVersion()),
                request -> {
                    rechecked.add(request.currentRequest().objectTokenDigest());
                    return new CompositeAuthorizationRecheckDecision(
                            CompositeAuthorizationRecheckOutcome.CURRENT, "CURRENT");
                },
                (facts, actor, action, traceId) -> {});

        service.get(ELIGIBILITY_ID, ACTOR, TRACE_ID);

        assertEquals(List.of(
                QualityEligibilityQueryService.digest("DEP-P0-CAMPUS-ACCESS-001"),
                QualityEligibilityQueryService.digest("DEP-P0-DORM-ACCESS-001"),
                QualityEligibilityQueryService.digest("DEP-P0-CAMPUS-ACCESS-001")),
                rechecked);
    }

    private static String code(Runnable action) {
        return assertThrows(IngestionQualityApplicationException.class, action::run).code();
    }

    private static CompositeAuthorizationDecision allow(long version) {
        return allow(version, 1);
    }

    private static CompositeAuthorizationDecision allow(long version, long identityGeneration) {
        return decision(CompositeAuthorizationOutcome.ALLOW, version, Map.of(
                "B", FieldVisibility.CLEAR, "I", FieldVisibility.MASKED,
                "C", FieldVisibility.HIDDEN, "S", FieldVisibility.HIDDEN,
                "E", FieldVisibility.CLEAR, "N", FieldVisibility.HIDDEN,
                "G", FieldVisibility.CLEAR, "T", FieldVisibility.CLEAR),
                identityGeneration);
    }

    private static CompositeAuthorizationDecision deny(long version) {
        return decision(CompositeAuthorizationOutcome.DENY, version, Map.of());
    }

    private static CompositeAuthorizationDecision decision(
            CompositeAuthorizationOutcome outcome, long version,
            Map<String, FieldVisibility> fields) {
        return decision(outcome, version, fields, 1);
    }

    private static CompositeAuthorizationDecision decision(
            CompositeAuthorizationOutcome outcome, long version,
            Map<String, FieldVisibility> fields, long identityGeneration) {
        return new CompositeAuthorizationDecision(
                outcome, outcome.name(), Set.of("R6-DATA-OWNER"), Set.of("OWNED_SOURCE"),
                fields, Set.of(), "RFP-1.0.0", version,
                Instant.parse("2026-08-10T00:00:00Z"),
                new CompositeAuthorizationDecisionToken(
                        identityGeneration, identityGeneration, identityGeneration,
                        identityGeneration, identityGeneration, version, "RFP-1.0.0"));
    }

    private static QualityEligibility eligibility() {
        return eligibility(List.of(
                member("SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001",
                        QualityEligibilityStatus.ELIGIBLE, 711),
                member("SRC-P0-DORM-ACCESS-001", "DEP-P0-DORM-ACCESS-001",
                        QualityEligibilityStatus.FUSED, 712)));
    }

    private static QualityEligibility eligibility(
            List<QualityEligibilityMemberEvidence> members) {
        return eligibility(
                ELIGIBILITY_ID, "ACC-SAFE-001", members,
                Instant.parse("2026-08-10T00:00:01Z"));
    }

    private static QualityEligibility eligibility(
            UUID eligibilityId, String ruleId, Instant occurredAt) {
        return eligibility(eligibilityId, ruleId, List.of(
                member("SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001",
                        QualityEligibilityStatus.ELIGIBLE, 711),
                member("SRC-P0-DORM-ACCESS-001", "DEP-P0-DORM-ACCESS-001",
                        QualityEligibilityStatus.FUSED, 712)), occurredAt);
    }

    private static QualityEligibility eligibility(
            UUID eligibilityId,
            String ruleId,
            List<QualityEligibilityMemberEvidence> members,
            Instant occurredAt) {
        return new QualityEligibility(
                eligibilityId, new RuleVersionIdentity(ruleId, "1.0.0"),
                "RULE-DEPENDENCY-REGISTRY-1.0.0", "sha256:" + "1".repeat(64),
                "DCC-1.1.0", "sha256:" + "2".repeat(64),
                "RC-1.0.0", "sha256:" + "3".repeat(64), 1,
                QualityEligibilityStatus.FUSED, QualityEligibilityReason.REQUIRED_MEMBER_FUSED,
                DependencyOperator.ALL_OF, null,
                members,
                List.of("DEP-P0-DORM-ACCESS-001"),
                Instant.parse("2026-08-10T00:00:00Z"),
                occurredAt, TRACE_ID);
    }

    private static QualityEligibilityMemberEvidence member(
            String sourceId, String dependencyId, QualityEligibilityStatus status, int suffix) {
        return member(sourceId, dependencyId, status, suffix, 1);
    }

    private static QualityEligibilityMemberEvidence member(
            String sourceId, String dependencyId, QualityEligibilityStatus status, int suffix,
            long dependencyVersion) {
        return new QualityEligibilityMemberEvidence(
                sourceId, 1, dependencyId, dependencyVersion,
                DependencyRequirement.REQUIRED, status, true,
                "source-watermark", "dependency-watermark",
                UUID.fromString("019fe570-0000-7000-8000-" + String.format("%012d", suffix)),
                "sha256:" + "4".repeat(64), "QMDP-1.0.0", "sha256:" + "5".repeat(64),
                "QSHM-1.0.0", "sha256:" + "6".repeat(64),
                UUID.fromString("019fe570-0000-7000-8000-" + String.format("%012d", suffix + 100)));
    }

    private static final class Store implements QualityEligibilityQueryPort {
        private final QualityEligibility fact;
        private final QualityEligibility refreshed;
        private int detailReads;

        private Store(QualityEligibility fact) { this(fact, fact); }

        private Store(QualityEligibility fact, QualityEligibility refreshed) {
            this.fact = fact;
            this.refreshed = refreshed;
        }

        @Override
        public List<QualityEligibility> findCurrent(QualityEligibilityQueryCriteria criteria) {
            return List.of(fact);
        }

        @Override
        public Optional<QualityEligibility> findCurrentById(UUID id) {
            detailReads++;
            QualityEligibility selected = detailReads == 1 ? fact : refreshed;
            return selected.eligibilityId().equals(id)
                    ? Optional.of(selected) : Optional.empty();
        }
    }

    private static final class PagingStore implements QualityEligibilityQueryPort {
        private final List<QualityEligibility> facts;

        private PagingStore(List<QualityEligibility> facts) {
            this.facts = List.copyOf(facts);
        }

        @Override
        public List<QualityEligibility> findCurrent(QualityEligibilityQueryCriteria criteria) {
            return facts.stream().filter(fact -> criteria.afterOccurredAt() == null
                            || fact.occurredAt().isBefore(criteria.afterOccurredAt())
                            || fact.occurredAt().equals(criteria.afterOccurredAt())
                            && fact.eligibilityId().compareTo(criteria.afterEligibilityId()) < 0)
                    .limit(criteria.limit()).toList();
        }

        @Override
        public Optional<QualityEligibility> findCurrentById(UUID id) {
            return facts.stream().filter(fact -> fact.eligibilityId().equals(id)).findFirst();
        }
    }
}
