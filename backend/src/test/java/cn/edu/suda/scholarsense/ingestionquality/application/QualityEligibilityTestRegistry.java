package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyRequirement;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyDefinition;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyMember;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleDependencyRegistry;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleVersionIdentity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class QualityEligibilityTestRegistry {
    private static final Map<String, List<Map.Entry<String, String>>> MEMBERS = Map.of(
            "ACC-SAFE-001", List.of(
                    Map.entry("SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001"),
                    Map.entry("SRC-P0-DORM-ACCESS-001", "DEP-P0-DORM-ACCESS-001"),
                    Map.entry("SRC-P0-ACCOMMODATION-001", "DEP-P0-ACCOMMODATION-001"),
                    Map.entry("SRC-P0-LEAVE-001", "DEP-P0-LEAVE-001"),
                    Map.entry("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001"),
                    Map.entry("SRC-P0-TIMETABLE-001", "DEP-P0-TIMETABLE-001"),
                    Map.entry("SRC-P0-DEVICE-001", "DEP-P0-DEVICE-001")),
            "ACC-SAFE-002", List.of(
                    Map.entry("SRC-P0-DORM-ACCESS-001", "DEP-P0-DORM-ACCESS-001"),
                    Map.entry("SRC-P0-ACCOMMODATION-001", "DEP-P0-ACCOMMODATION-001"),
                    Map.entry("SRC-P0-LEAVE-001", "DEP-P0-LEAVE-001"),
                    Map.entry("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001"),
                    Map.entry("SRC-P0-TIMETABLE-001", "DEP-P0-TIMETABLE-001"),
                    Map.entry("SRC-P0-DEVICE-001", "DEP-P0-DEVICE-001")),
            "ECON-012", List.of(
                    Map.entry("SRC-P0-CARD-001", "DEP-P0-CONSUMPTION-001"),
                    Map.entry("SRC-P0-LEAVE-001", "DEP-P0-LEAVE-001"),
                    Map.entry("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001"),
                    Map.entry("SRC-P1-OFFCAMPUS-001", "DEP-P1-OFFCAMPUS-001")),
            "NIGHT-001", List.of(
                    Map.entry("SRC-P1-NETWORK-001", "DEP-P1-NETWORK-001"),
                    Map.entry("SRC-P0-LEAVE-001", "DEP-P0-LEAVE-001"),
                    Map.entry("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001"),
                    Map.entry("SRC-P1-OFFCAMPUS-001", "DEP-P1-OFFCAMPUS-001")),
            "ACADEMIC-001", List.of(
                    Map.entry("SRC-P1-ACADEMIC-001", "DEP-P1-ACADEMIC-001"),
                    Map.entry("SRC-P0-TIMETABLE-001", "DEP-P0-TIMETABLE-001"),
                    Map.entry("SRC-P0-CALENDAR-001", "DEP-P0-CALENDAR-001")));

    private QualityEligibilityTestRegistry() {}

    static RuleDependencyRegistry completeProductionRegistry(
            List<RuleDependencyDefinition> ignoredSeeds) {
        ArrayList<RuleDependencyDefinition> definitions = new ArrayList<>();
        for (Map.Entry<String, List<Map.Entry<String, String>>> rule :
                new LinkedHashMap<>(MEMBERS).entrySet()) {
            definitions.add(new RuleDependencyDefinition(
                    new RuleVersionIdentity(rule.getKey(), "1.0.0"),
                    DependencyOperator.ALL_OF, null,
                    rule.getValue().stream().map(member -> new RuleDependencyMember(
                            member.getKey(), "SOURCE-1.0.0", member.getValue(), "1.0.0",
                            DependencyRequirement.REQUIRED, "primary")).toList()));
        }
        return RuleDependencyRegistry.production(
                "sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64),
                "sha256:" + "3".repeat(64), definitions);
    }
}
