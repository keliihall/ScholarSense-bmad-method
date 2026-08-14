package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record RuleDependencyRegistry(
        String registryVersion,
        String registryDigest,
        String catalogVersion,
        String catalogDigest,
        String ruleCatalogVersion,
        String ruleCatalogDigest,
        List<RuleDependencyDefinition> rules) {

    private static final String VERSION = "RULE-DEPENDENCY-REGISTRY-1.0.0";
    private static final String CATALOG_VERSION = "DCC-1.1.0";
    private static final String RULE_CATALOG_VERSION = "RC-1.0.0";
    private static final Map<String, String> IDENTITY_ORACLE = Map.ofEntries(
            Map.entry("SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001"),
            Map.entry("SRC-P0-DORM-ACCESS-001", "DEP-P0-DORM-ACCESS-001"),
            Map.entry("SRC-P0-ACCOMMODATION-001", "DEP-P0-ACCOMMODATION-001"),
            Map.entry("SRC-P0-LEAVE-001", "DEP-P0-LEAVE-001"),
            Map.entry("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001"),
            Map.entry("SRC-P0-CARD-001", "DEP-P0-CONSUMPTION-001"),
            Map.entry("SRC-P0-TIMETABLE-001", "DEP-P0-TIMETABLE-001"),
            Map.entry("SRC-P0-DEVICE-001", "DEP-P0-DEVICE-001"),
            Map.entry("SRC-P1-OFFCAMPUS-001", "DEP-P1-OFFCAMPUS-001"),
            Map.entry("SRC-P1-NETWORK-001", "DEP-P1-NETWORK-001"),
            Map.entry("SRC-P1-ACADEMIC-001", "DEP-P1-ACADEMIC-001"));
    private static final Map<String, Set<String>> RULE_ORACLE = Map.of(
            "ACC-SAFE-001", Set.of(
                    "DEP-P0-CAMPUS-ACCESS-001", "DEP-P0-DORM-ACCESS-001",
                    "DEP-P0-ACCOMMODATION-001", "DEP-P0-LEAVE-001",
                    "DEP-P0-CALENDAR-001", "DEP-P0-TIMETABLE-001", "DEP-P0-DEVICE-001"),
            "ACC-SAFE-002", Set.of(
                    "DEP-P0-DORM-ACCESS-001", "DEP-P0-ACCOMMODATION-001",
                    "DEP-P0-LEAVE-001", "DEP-P0-CALENDAR-001",
                    "DEP-P0-TIMETABLE-001", "DEP-P0-DEVICE-001"),
            "ECON-012", Set.of(
                    "DEP-P0-CONSUMPTION-001", "DEP-P0-LEAVE-001",
                    "DEP-P0-CALENDAR-001", "DEP-P1-OFFCAMPUS-001"),
            "NIGHT-001", Set.of(
                    "DEP-P1-NETWORK-001", "DEP-P0-LEAVE-001",
                    "DEP-P0-CALENDAR-001", "DEP-P1-OFFCAMPUS-001"),
            "ACADEMIC-001", Set.of(
                    "DEP-P1-ACADEMIC-001", "DEP-P0-TIMETABLE-001",
                    "DEP-P0-CALENDAR-001"));

    public RuleDependencyRegistry {
        requireExact(registryVersion, VERSION);
        registryDigest = requireDigest(registryDigest);
        requireExact(catalogVersion, CATALOG_VERSION);
        catalogDigest = requireDigest(catalogDigest);
        requireExact(ruleCatalogVersion, RULE_CATALOG_VERSION);
        ruleCatalogDigest = requireDigest(ruleCatalogDigest);
        rules = List.copyOf(Objects.requireNonNull(rules)).stream()
                .sorted(Comparator.comparing(rule -> rule.ruleVersion().ruleId()))
                .toList();
        validateProductionRules(rules);
    }

    public static RuleDependencyRegistry production(
            String registryDigest,
            String catalogDigest,
            String ruleCatalogDigest,
            List<RuleDependencyDefinition> rules) {
        return new RuleDependencyRegistry(
                VERSION, registryDigest, CATALOG_VERSION, catalogDigest,
                RULE_CATALOG_VERSION, ruleCatalogDigest, rules);
    }

    public RuleDependencyDefinition requireRule(RuleVersionIdentity identity) {
        return rules.stream().filter(rule -> rule.ruleVersion().equals(identity)).findFirst()
                .orElseThrow(RuleDependencyRegistry::invalid);
    }

    private static void validateProductionRules(List<RuleDependencyDefinition> definitions) {
        if (definitions.size() != RULE_ORACLE.size()) throw invalid();
        Map<String, RuleDependencyDefinition> byRule = new LinkedHashMap<>();
        for (RuleDependencyDefinition definition : definitions) {
            String ruleId = definition.ruleVersion().ruleId();
            if (byRule.put(ruleId, definition) != null
                    || !"1.0.0".equals(definition.ruleVersion().ruleVersion())
                    || definition.operator() != DependencyOperator.ALL_OF
                    || definition.threshold() != null
                    || definition.members().stream().anyMatch(member ->
                            member.requirement() != DependencyRequirement.REQUIRED)) {
                throw invalid();
            }
            Set<String> expected = RULE_ORACLE.get(ruleId);
            Set<String> actual = Set.copyOf(
                    definition.members().stream().map(RuleDependencyMember::dependencyId).toList());
            if (expected == null || !expected.equals(actual)) throw invalid();
            for (RuleDependencyMember member : definition.members()) {
                if (!Objects.equals(
                        IDENTITY_ORACLE.get(member.sourceId()), member.dependencyId())) {
                    throw invalid();
                }
            }
        }
        if (!byRule.keySet().equals(RULE_ORACLE.keySet())) throw invalid();
        Set<String> dependencies = definitions.stream()
                .flatMap(rule -> rule.members().stream())
                .map(RuleDependencyMember::dependencyId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!dependencies.equals(Set.copyOf(IDENTITY_ORACLE.values()))) throw invalid();
    }

    private static String requireDigest(String value) {
        try {
            return IngestionQualityDomainRules.requireSha256(value);
        } catch (IngestionQualityException error) {
            throw new IngestionQualityException(
                    IngestionQualityErrorCode.INGESTION_QUALITY_REGISTRY_DIGEST_MISMATCH);
        }
    }

    private static void requireExact(String actual, String expected) {
        if (!expected.equals(actual)) throw invalid();
    }

    private static IngestionQualityException invalid() {
        return new IngestionQualityException(
                IngestionQualityErrorCode.INGESTION_QUALITY_REGISTRY_INVALID);
    }
}
