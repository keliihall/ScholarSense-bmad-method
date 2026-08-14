package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;

final class RecoveryValidationPortRules {
    private static final String UUID_V7 =
            "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    private RecoveryValidationPortRules() {}

    static void idsAndDigests(
            String requestId, String episodeId, String taskId, String traceId,
            String... digests) {
        if (requestId == null || !requestId.matches(UUID_V7)
                || episodeId == null || !episodeId.matches(UUID_V7)
                || taskId == null || !taskId.matches(UUID_V7)
                || !trace(traceId)) throw invalid();
        for (String digest : digests) if (!digest(digest)) throw invalid();
    }

    static void result(
            RecoveryValidationDependencyAvailability availability,
            boolean completed,
            String firstDigest,
            String secondDigest,
            long count,
            String summaryDigest,
            Instant completedAt,
            RecoveryValidationDependencyError error,
            boolean retryable,
            String traceId) {
        boolean available = availability == RecoveryValidationDependencyAvailability.AVAILABLE;
        if (availability == null || available != completed || count < 0
                || available != digest(firstDigest) || available != digest(secondDigest)
                || available != digest(summaryDigest) || available != (completedAt != null)
                || available != (error == null) || available && retryable || !trace(traceId)) {
            throw invalid();
        }
    }

    static boolean digest(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    static boolean trace(String value) {
        return value != null && value.matches("(?!0{32})[0-9a-f]{32}");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("RECOVERY_VALIDATION_PORT_VALUE_INVALID");
    }
}
