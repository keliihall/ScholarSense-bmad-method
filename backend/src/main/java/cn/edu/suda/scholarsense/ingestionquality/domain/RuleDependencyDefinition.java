package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record RuleDependencyDefinition(
        RuleVersionIdentity ruleVersion,
        DependencyOperator operator,
        Integer threshold,
        List<RuleDependencyMember> members) {

    public RuleDependencyDefinition {
        ruleVersion = Objects.requireNonNull(ruleVersion);
        operator = Objects.requireNonNull(operator);
        members = List.copyOf(Objects.requireNonNull(members)).stream()
                .sorted(Comparator.comparing(RuleDependencyMember::dependencyId))
                .toList();
        if (members.isEmpty() || members.size() > 11
                || new HashSet<>(members.stream().map(RuleDependencyMember::sourceId).toList()).size()
                        != members.size()
                || new HashSet<>(members.stream().map(RuleDependencyMember::dependencyId).toList()).size()
                        != members.size()) {
            throw invalid();
        }
        if (operator == DependencyOperator.THRESHOLD) {
            if (threshold == null || threshold < 1 || threshold > members.size()) {
                throw invalid();
            }
        } else if (threshold != null) {
            throw invalid();
        }
    }

    private static IngestionQualityException invalid() {
        return new IngestionQualityException(
                IngestionQualityErrorCode.INGESTION_QUALITY_COMPOSITION_INVALID);
    }
}
