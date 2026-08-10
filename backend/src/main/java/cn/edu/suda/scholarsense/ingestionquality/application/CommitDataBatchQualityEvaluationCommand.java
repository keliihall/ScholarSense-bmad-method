package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;

public record CommitDataBatchQualityEvaluationCommand(
        long expectedAggregateVersion,
        PreparedDataBatchAssessment assessment,
        String retentionScopeDigest,
        DataBatchAtomicCommitContext commit,
        CanonicalOutboxPayload outbox) {
    public CommitDataBatchQualityEvaluationCommand {
        expectedAggregateVersion = DataBatchCommandRules.expectedVersion(
                expectedAggregateVersion);
        Objects.requireNonNull(assessment);
        retentionScopeDigest = DataBatchCommandRules.digest(retentionScopeDigest);
        Objects.requireNonNull(commit);
        Objects.requireNonNull(outbox);
        if (assessment.updatedBatch().aggregateVersion() != expectedAggregateVersion + 1) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
    }
}
