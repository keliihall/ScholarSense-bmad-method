package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class QualityEligibilityEvaluator {

    public QualityEligibilityDecision evaluate(
            RuleDependencyDefinition definition, List<DependencyQualityState> states) {
        Objects.requireNonNull(definition);
        Map<String, DependencyQualityState> byDependency = new LinkedHashMap<>();
        for (DependencyQualityState state : List.copyOf(Objects.requireNonNull(states))) {
            if (byDependency.put(state.dependencyId(), state) != null) throw invalid();
            RuleDependencyMember member = definition.members().stream()
                    .filter(candidate -> candidate.dependencyId().equals(state.dependencyId()))
                    .findFirst().orElseThrow(QualityEligibilityEvaluator::invalid);
            if (!member.sourceId().equals(state.sourceId())) throw invalid();
        }

        ArrayList<String> failed = new ArrayList<>();
        boolean fused = false;
        boolean recovering = false;
        boolean missing = false;
        boolean versionGap = false;
        for (RuleDependencyMember member : definition.members()) {
            if (member.requirement() != DependencyRequirement.REQUIRED) continue;
            DependencyQualityState state = byDependency.get(member.dependencyId());
            if (state == null || state.status() == QualityEligibilityStatus.MISSING) {
                missing = true;
                failed.add(member.dependencyId());
            } else if (!state.versionContinuous()) {
                versionGap = true;
                failed.add(member.dependencyId());
            } else if (state.status() == QualityEligibilityStatus.FUSED) {
                fused = true;
                failed.add(member.dependencyId());
            } else if (state.status() == QualityEligibilityStatus.RECOVERING) {
                recovering = true;
                failed.add(member.dependencyId());
            }
        }
        if (versionGap) {
            return decision(QualityEligibilityStatus.FUSED,
                    QualityEligibilityReason.REQUIRED_MEMBER_VERSION_GAP, failed);
        }
        if (fused) {
            return decision(QualityEligibilityStatus.FUSED,
                    QualityEligibilityReason.REQUIRED_MEMBER_FUSED, failed);
        }
        if (recovering) {
            return decision(QualityEligibilityStatus.RECOVERING,
                    QualityEligibilityReason.REQUIRED_MEMBER_RECOVERING, failed);
        }
        if (missing) {
            return decision(QualityEligibilityStatus.MISSING,
                    QualityEligibilityReason.REQUIRED_MEMBER_MISSING, failed);
        }

        long eligibleCount = definition.members().stream()
                .map(member -> byDependency.get(member.dependencyId()))
                .filter(Objects::nonNull)
                .filter(DependencyQualityState::versionContinuous)
                .filter(state -> state.status() == QualityEligibilityStatus.ELIGIBLE)
                .count();
        return switch (definition.operator()) {
            case ALL_OF -> decision(QualityEligibilityStatus.ELIGIBLE,
                    QualityEligibilityReason.ALL_REQUIRED_ELIGIBLE, List.of());
            case ANY_OF -> eligibleCount >= 1
                    ? decision(QualityEligibilityStatus.ELIGIBLE,
                            QualityEligibilityReason.ALL_REQUIRED_ELIGIBLE, List.of())
                    : decision(QualityEligibilityStatus.FUSED,
                            QualityEligibilityReason.ANY_OF_UNSATISFIED,
                            nonEligibleMembers(definition, byDependency));
            case THRESHOLD -> eligibleCount >= definition.threshold()
                    ? decision(QualityEligibilityStatus.ELIGIBLE,
                            QualityEligibilityReason.ALL_REQUIRED_ELIGIBLE, List.of())
                    : decision(QualityEligibilityStatus.FUSED,
                            QualityEligibilityReason.THRESHOLD_UNSATISFIED,
                            nonEligibleMembers(definition, byDependency));
        };
    }

    private static List<String> nonEligibleMembers(
            RuleDependencyDefinition definition,
            Map<String, DependencyQualityState> states) {
        return definition.members().stream().filter(member -> {
            DependencyQualityState state = states.get(member.dependencyId());
            return state == null || !state.versionContinuous()
                    || state.status() != QualityEligibilityStatus.ELIGIBLE;
        }).map(RuleDependencyMember::dependencyId).toList();
    }

    private static QualityEligibilityDecision decision(
            QualityEligibilityStatus status,
            QualityEligibilityReason reason,
            List<String> failedMembers) {
        return new QualityEligibilityDecision(status, reason, failedMembers);
    }

    private static IngestionQualityException invalid() {
        return new IngestionQualityException(
                IngestionQualityErrorCode.INGESTION_QUALITY_UPSTREAM_BINDING_INVALID);
    }
}
