package cn.edu.suda.scholarsense.signalevaluation.application;

/** Durable, PII-free idempotency value owned by signal-evaluation. */
public record RecoverySampleReplayEntry(
        String providerVersion,
        String requestDigest,
        String canonicalBodyDigest,
        RecoverySampleComputationResponse response) {

    public RecoverySampleReplayEntry {
        if (providerVersion == null
                || requestDigest == null
                || !requestDigest.matches("sha256:[0-9a-f]{64}")
                || canonicalBodyDigest == null
                || !canonicalBodyDigest.matches("sha256:[0-9a-f]{64}")
                || response == null) {
            throw new IllegalArgumentException("RECOVERY_SAMPLE_REPLAY_INVALID");
        }
    }
}
