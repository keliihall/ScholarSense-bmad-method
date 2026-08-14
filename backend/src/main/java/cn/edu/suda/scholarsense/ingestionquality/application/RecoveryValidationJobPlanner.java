package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.IngestionQualityErrorCode;
import cn.edu.suda.scholarsense.ingestionquality.domain.IngestionQualityException;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationJob;
import java.util.Objects;

/** Creates one durable validation job per idempotency key and rejects same-key drift. */
public final class RecoveryValidationJobPlanner {
    private final RecoveryValidationSubmissionPort submissions;

    public RecoveryValidationJobPlanner(RecoveryValidationSubmissionPort submissions) {
        this.submissions = Objects.requireNonNull(submissions);
    }

    public RecoveryValidationSubmissionResult submit(RecoveryValidationSubmission request) {
        Objects.requireNonNull(request);
        RecoveryValidationJob existing = submissions
                .findByIdempotencyKeyDigest(request.idempotencyKeyDigest())
                .orElse(null);
        if (existing != null) return replay(existing, request.inputDigest());

        RecoveryValidationJob persisted = Objects.requireNonNull(submissions.insertIfAbsent(
                request.idempotencyKeyDigest(), request.job()));
        if (persisted == request.job()) {
            return new RecoveryValidationSubmissionResult(persisted, false);
        }
        return replay(persisted, request.inputDigest());
    }

    private static RecoveryValidationSubmissionResult replay(
            RecoveryValidationJob existing, String requestedInputDigest) {
        if (!existing.binding().inputDigest().equals(requestedInputDigest)) {
            throw new IngestionQualityException(
                    IngestionQualityErrorCode.INGESTION_QUALITY_IDEMPOTENCY_MISMATCH);
        }
        return new RecoveryValidationSubmissionResult(existing, true);
    }
}
