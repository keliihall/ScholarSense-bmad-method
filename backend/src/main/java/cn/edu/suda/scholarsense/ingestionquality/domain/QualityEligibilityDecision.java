package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.List;
import java.util.Objects;

public record QualityEligibilityDecision(
        QualityEligibilityStatus status,
        QualityEligibilityReason reason,
        List<String> failedMembers) {

    public QualityEligibilityDecision {
        status = Objects.requireNonNull(status);
        reason = Objects.requireNonNull(reason);
        failedMembers = List.copyOf(Objects.requireNonNull(failedMembers));
    }
}
