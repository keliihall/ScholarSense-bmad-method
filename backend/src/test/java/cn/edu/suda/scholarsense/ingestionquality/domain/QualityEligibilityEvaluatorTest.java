package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QualityEligibilityEvaluatorTest {

    private static final String REGISTRY_DIGEST = "sha256:" + "1".repeat(64);
    private static final String CATALOG_DIGEST = "sha256:" + "2".repeat(64);
    private static final String RULE_CATALOG_DIGEST = "sha256:" + "3".repeat(64);

    @Test
    void requiredAllOfFailsClosedForFusedRecoveringMissingAndVersionGap() {
        RuleDependencyDefinition definition = definition(
                "ACC-SAFE-001", DependencyOperator.ALL_OF, null,
                member("SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001", DependencyRequirement.REQUIRED),
                member("SRC-P0-DORM-ACCESS-001", "DEP-P0-DORM-ACCESS-001", DependencyRequirement.REQUIRED));

        assertDecision(definition, QualityEligibilityStatus.FUSED,
                QualityEligibilityReason.REQUIRED_MEMBER_FUSED,
                state("SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001", QualityEligibilityStatus.ELIGIBLE, true),
                state("SRC-P0-DORM-ACCESS-001", "DEP-P0-DORM-ACCESS-001", QualityEligibilityStatus.FUSED, true));
        assertDecision(definition, QualityEligibilityStatus.RECOVERING,
                QualityEligibilityReason.REQUIRED_MEMBER_RECOVERING,
                state("SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001", QualityEligibilityStatus.ELIGIBLE, true),
                state("SRC-P0-DORM-ACCESS-001", "DEP-P0-DORM-ACCESS-001", QualityEligibilityStatus.RECOVERING, true));
        assertDecision(definition, QualityEligibilityStatus.MISSING,
                QualityEligibilityReason.REQUIRED_MEMBER_MISSING,
                state("SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001", QualityEligibilityStatus.ELIGIBLE, true));
        assertDecision(definition, QualityEligibilityStatus.FUSED,
                QualityEligibilityReason.REQUIRED_MEMBER_VERSION_GAP,
                state("SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001", QualityEligibilityStatus.ELIGIBLE, true),
                state("SRC-P0-DORM-ACCESS-001", "DEP-P0-DORM-ACCESS-001", QualityEligibilityStatus.ELIGIBLE, false));
    }

    @Test
    void optionalMembersCannotMaskRequiredFailureAndDoNotBlockAllOf() {
        RuleDependencyDefinition definition = definition(
                "SYNTHETIC-001", DependencyOperator.ALL_OF, null,
                member("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001", DependencyRequirement.REQUIRED),
                member("SRC-P1-NETWORK-001", "DEP-P1-NETWORK-001", DependencyRequirement.OPTIONAL));

        assertDecision(definition, QualityEligibilityStatus.ELIGIBLE,
                QualityEligibilityReason.ALL_REQUIRED_ELIGIBLE,
                state("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001", QualityEligibilityStatus.ELIGIBLE, true));
        assertDecision(definition, QualityEligibilityStatus.FUSED,
                QualityEligibilityReason.REQUIRED_MEMBER_FUSED,
                state("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001", QualityEligibilityStatus.FUSED, true),
                state("SRC-P1-NETWORK-001", "DEP-P1-NETWORK-001", QualityEligibilityStatus.ELIGIBLE, true));
    }

    @Test
    void anyOfAndThresholdUseExactBoundaryWithoutChangingProductionRegistry() {
        RuleDependencyDefinition anyOf = definition(
                "SYNTHETIC-ANY", DependencyOperator.ANY_OF, null,
                member("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001", DependencyRequirement.OPTIONAL),
                member("SRC-P1-NETWORK-001", "DEP-P1-NETWORK-001", DependencyRequirement.OPTIONAL));
        assertDecision(anyOf, QualityEligibilityStatus.ELIGIBLE,
                QualityEligibilityReason.ALL_REQUIRED_ELIGIBLE,
                state("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001", QualityEligibilityStatus.FUSED, true),
                state("SRC-P1-NETWORK-001", "DEP-P1-NETWORK-001", QualityEligibilityStatus.ELIGIBLE, true));

        RuleDependencyDefinition threshold = definition(
                "SYNTHETIC-THRESHOLD", DependencyOperator.THRESHOLD, 2,
                member("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001", DependencyRequirement.OPTIONAL),
                member("SRC-P1-NETWORK-001", "DEP-P1-NETWORK-001", DependencyRequirement.OPTIONAL),
                member("SRC-P0-LEAVE-001", "DEP-P0-LEAVE-001", DependencyRequirement.OPTIONAL));
        assertDecision(threshold, QualityEligibilityStatus.ELIGIBLE,
                QualityEligibilityReason.ALL_REQUIRED_ELIGIBLE,
                state("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001", QualityEligibilityStatus.ELIGIBLE, true),
                state("SRC-P1-NETWORK-001", "DEP-P1-NETWORK-001", QualityEligibilityStatus.ELIGIBLE, true),
                state("SRC-P0-LEAVE-001", "DEP-P0-LEAVE-001", QualityEligibilityStatus.FUSED, true));
        assertDecision(threshold, QualityEligibilityStatus.FUSED,
                QualityEligibilityReason.THRESHOLD_UNSATISFIED,
                state("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001", QualityEligibilityStatus.ELIGIBLE, true),
                state("SRC-P1-NETWORK-001", "DEP-P1-NETWORK-001", QualityEligibilityStatus.FUSED, true),
                state("SRC-P0-LEAVE-001", "DEP-P0-LEAVE-001", QualityEligibilityStatus.FUSED, true));

        assertThrows(IngestionQualityException.class, () -> definition(
                "SYNTHETIC-BAD", DependencyOperator.THRESHOLD, 4,
                member("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001", DependencyRequirement.OPTIONAL)));
    }

    @Test
    void registryRequiresTheExactFiveProductionRulesAndElevenStableIdentities() {
        RuleDependencyRegistry registry = productionRegistry();
        assertEquals(5, registry.rules().size());
        assertEquals(11, registry.rules().stream()
                .flatMap(rule -> rule.members().stream())
                .map(RuleDependencyMember::dependencyId)
                .distinct().count());
        assertTrue(registry.rules().stream().allMatch(rule ->
                rule.operator() == DependencyOperator.ALL_OF
                        && rule.members().stream().allMatch(member ->
                                member.requirement() == DependencyRequirement.REQUIRED)));

        List<RuleDependencyDefinition> incomplete = new ArrayList<>(registry.rules());
        incomplete.removeLast();
        assertThrows(IngestionQualityException.class, () -> new RuleDependencyRegistry(
                "RULE-DEPENDENCY-REGISTRY-1.0.0", REGISTRY_DIGEST,
                "DCC-1.1.0", CATALOG_DIGEST, "RC-1.0.0", RULE_CATALOG_DIGEST,
                incomplete));
    }

    @Test
    void registryRejectsIdentityAndDigestDrift() {
        List<RuleDependencyDefinition> rules = new ArrayList<>(productionRegistry().rules());
        RuleDependencyDefinition first = rules.getFirst();
        List<RuleDependencyMember> driftedMembers = new ArrayList<>(first.members());
        driftedMembers.set(0, member(
                "SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-DORM-ACCESS-001",
                DependencyRequirement.REQUIRED));
        rules.set(0, new RuleDependencyDefinition(
                first.ruleVersion(), first.operator(), first.threshold(), driftedMembers));
        assertThrows(IngestionQualityException.class, () -> new RuleDependencyRegistry(
                "RULE-DEPENDENCY-REGISTRY-1.0.0", REGISTRY_DIGEST,
                "DCC-1.1.0", CATALOG_DIGEST, "RC-1.0.0", RULE_CATALOG_DIGEST, rules));
        assertThrows(IngestionQualityException.class, () -> new RuleDependencyRegistry(
                "RULE-DEPENDENCY-REGISTRY-1.0.0", "sha256:drift",
                "DCC-1.1.0", CATALOG_DIGEST, "RC-1.0.0", RULE_CATALOG_DIGEST,
                productionRegistry().rules()));
    }

    @Test
    void immutableEligibilityDefensivelyCopiesMembersAndFailedIdentities() {
        ArrayList<QualityEligibilityMemberEvidence> members = new ArrayList<>(List.of(
                evidence("SRC-P0-DORM-ACCESS-001", "DEP-P0-DORM-ACCESS-001",
                        QualityEligibilityStatus.FUSED)));
        ArrayList<String> failed = new ArrayList<>(List.of("DEP-P0-DORM-ACCESS-001"));
        QualityEligibility fact = new QualityEligibility(
                uuid("019d2c7d-4000-7000-8000-000000000301"),
                new RuleVersionIdentity("ACC-SAFE-001", "1.0.0"),
                "RULE-DEPENDENCY-REGISTRY-1.0.0", REGISTRY_DIGEST,
                "DCC-1.1.0", CATALOG_DIGEST, "RC-1.0.0", RULE_CATALOG_DIGEST,
                1, QualityEligibilityStatus.FUSED,
                QualityEligibilityReason.REQUIRED_MEMBER_FUSED,
                DependencyOperator.ALL_OF, null, members, failed,
                Instant.parse("2026-08-10T11:31:00Z"),
                Instant.parse("2026-08-10T11:31:55Z"),
                "11111111111111111111111111111111");
        members.clear();
        failed.clear();

        assertEquals(1, fact.members().size());
        assertEquals(List.of("DEP-P0-DORM-ACCESS-001"), fact.failedMembers());
        assertNotSame(members, fact.members());
        assertThrows(UnsupportedOperationException.class, () -> fact.members().clear());
        assertThrows(UnsupportedOperationException.class, () -> fact.failedMembers().clear());
    }

    private static void assertDecision(
            RuleDependencyDefinition definition,
            QualityEligibilityStatus status,
            QualityEligibilityReason reason,
            DependencyQualityState... states) {
        QualityEligibilityDecision decision = new QualityEligibilityEvaluator()
                .evaluate(definition, List.of(states));
        assertEquals(status, decision.status());
        assertEquals(reason, decision.reason());
    }

    private static RuleDependencyDefinition definition(
            String ruleId,
            DependencyOperator operator,
            Integer threshold,
            RuleDependencyMember... members) {
        return new RuleDependencyDefinition(
                new RuleVersionIdentity(ruleId, "1.0.0"), operator, threshold, List.of(members));
    }

    private static RuleDependencyMember member(
            String sourceId, String dependencyId, DependencyRequirement requirement) {
        return new RuleDependencyMember(
                sourceId, "SOURCE-CONTRACT-1.0.0", dependencyId, "1.0.0",
                requirement, "primary");
    }

    private static DependencyQualityState state(
            String sourceId,
            String dependencyId,
            QualityEligibilityStatus status,
            boolean continuous) {
        return new DependencyQualityState(
                sourceId, 12, uuid("019d2c7d-4000-7000-8000-000000000310"), 1,
                dependencyId, 12, status, continuous, "opaque-watermark");
    }

    private static QualityEligibilityMemberEvidence evidence(
            String sourceId, String dependencyId, QualityEligibilityStatus status) {
        return new QualityEligibilityMemberEvidence(
                sourceId, 12, dependencyId, 12, DependencyRequirement.REQUIRED,
                status, true, "opaque-source-watermark", "opaque-dependency-watermark",
                uuid("019d2c7d-4000-7000-8000-000000000311"),
                "sha256:" + "4".repeat(64), "QMDP-1.0.0", "sha256:" + "5".repeat(64),
                "QSHM-1.0.0", "sha256:" + "6".repeat(64),
                uuid("019d2c7d-4000-7000-8000-000000000312"));
    }

    private static RuleDependencyRegistry productionRegistry() {
        return RuleDependencyRegistry.production(
                REGISTRY_DIGEST, CATALOG_DIGEST, RULE_CATALOG_DIGEST, List.of(
                        definition("ACC-SAFE-001", DependencyOperator.ALL_OF, null,
                                member("SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-DORM-ACCESS-001", "DEP-P0-DORM-ACCESS-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-ACCOMMODATION-001", "DEP-P0-ACCOMMODATION-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-LEAVE-001", "DEP-P0-LEAVE-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-TIMETABLE-001", "DEP-P0-TIMETABLE-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-DEVICE-001", "DEP-P0-DEVICE-001", DependencyRequirement.REQUIRED)),
                        definition("ACC-SAFE-002", DependencyOperator.ALL_OF, null,
                                member("SRC-P0-DORM-ACCESS-001", "DEP-P0-DORM-ACCESS-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-ACCOMMODATION-001", "DEP-P0-ACCOMMODATION-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-LEAVE-001", "DEP-P0-LEAVE-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-TIMETABLE-001", "DEP-P0-TIMETABLE-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-DEVICE-001", "DEP-P0-DEVICE-001", DependencyRequirement.REQUIRED)),
                        definition("ECON-012", DependencyOperator.ALL_OF, null,
                                member("SRC-P0-CARD-001", "DEP-P0-CONSUMPTION-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-LEAVE-001", "DEP-P0-LEAVE-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001", DependencyRequirement.REQUIRED),
                                member("SRC-P1-OFFCAMPUS-001", "DEP-P1-OFFCAMPUS-001", DependencyRequirement.REQUIRED)),
                        definition("NIGHT-001", DependencyOperator.ALL_OF, null,
                                member("SRC-P1-NETWORK-001", "DEP-P1-NETWORK-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-LEAVE-001", "DEP-P0-LEAVE-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001", DependencyRequirement.REQUIRED),
                                member("SRC-P1-OFFCAMPUS-001", "DEP-P1-OFFCAMPUS-001", DependencyRequirement.REQUIRED)),
                        definition("ACADEMIC-001", DependencyOperator.ALL_OF, null,
                                member("SRC-P1-ACADEMIC-001", "DEP-P1-ACADEMIC-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-TIMETABLE-001", "DEP-P0-TIMETABLE-001", DependencyRequirement.REQUIRED),
                                member("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001", DependencyRequirement.REQUIRED))));
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
