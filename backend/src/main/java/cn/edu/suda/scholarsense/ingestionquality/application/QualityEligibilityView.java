package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibility;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityMemberEvidence;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** R6 Dependency projection; no student, raw-record, metric result, or trace fields. */
public record QualityEligibilityView(
        UUID eligibilityId,
        String ruleId,
        String ruleVersion,
        String status,
        String reasonCode,
        String operator,
        Integer threshold,
        List<Member> members,
        List<String> failedMembers,
        String registryVersion,
        long aggregateVersion,
        Instant effectiveAt,
        Instant occurredAt) {
    public QualityEligibilityView {
        members = List.copyOf(members);
        failedMembers = List.copyOf(failedMembers);
    }

    public static QualityEligibilityView from(QualityEligibility value) {
        return new QualityEligibilityView(
                value.eligibilityId(), value.ruleVersion().ruleId(),
                value.ruleVersion().ruleVersion(), value.status().wireValue(),
                value.reason().name(), operator(value.operator()), value.threshold(),
                value.members().stream().map(Member::from).toList(), value.failedMembers(),
                value.registryVersion(), value.aggregateVersion(), value.effectiveAt(),
                value.occurredAt());
    }

    private static String operator(DependencyOperator value) {
        return switch (value) {
            case ALL_OF -> "all-of";
            case ANY_OF -> "any-of";
            case THRESHOLD -> "threshold";
        };
    }

    public record Member(
            String sourceId,
            long sourceVersion,
            String dependencyId,
            long dependencyVersion,
            String requirement,
            String state,
            boolean versionContinuous,
            String sourceWatermark,
            String dependencyWatermark,
            UUID snapshotId,
            String snapshotImmutableHash) {
        static Member from(QualityEligibilityMemberEvidence value) {
            return new Member(
                    value.sourceId(), value.sourceVersion(), value.dependencyId(),
                    value.dependencyVersion(), value.requirement().name().toLowerCase(
                            java.util.Locale.ROOT), value.state().wireValue(),
                    value.versionContinuous(), value.sourceWatermark(),
                    value.dependencyWatermark(), value.snapshotId(),
                    value.snapshotImmutableHash());
        }
    }
}
