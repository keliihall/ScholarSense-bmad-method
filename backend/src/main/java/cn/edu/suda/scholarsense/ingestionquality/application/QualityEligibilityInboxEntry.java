package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;

public record QualityEligibilityInboxEntry(
        String payloadDigest,
        QualityEligibilityProcessingOutcome outcome,
        QualityFuseTaskPlan fuseTaskPlan) {
    public QualityEligibilityInboxEntry {
        payloadDigest = Objects.requireNonNull(payloadDigest);
        outcome = Objects.requireNonNull(outcome);
    }

    public QualityEligibilityInboxEntry(
            String payloadDigest, QualityEligibilityProcessingOutcome outcome) {
        this(payloadDigest, outcome, null);
    }
}
