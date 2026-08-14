package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryValidationJob;

public record RecoveryValidationSubmission(
        String idempotencyKeyDigest,
        String inputDigest,
        RecoveryValidationJob job) {

    public RecoveryValidationSubmission {
        requireDigest(idempotencyKeyDigest);
        requireDigest(inputDigest);
        if (job == null || !inputDigest.equals(job.binding().inputDigest())) {
            throw new IllegalArgumentException("INGESTION_QUALITY_VALIDATION_SUBMISSION_INVALID");
        }
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_VALIDATION_SUBMISSION_INVALID");
        }
    }
}
