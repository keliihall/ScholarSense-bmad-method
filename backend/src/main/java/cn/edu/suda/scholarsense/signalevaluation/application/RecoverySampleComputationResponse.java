package cn.edu.suda.scholarsense.signalevaluation.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Internal closed response; the public API facade performs the outward projection. */
public record RecoverySampleComputationResponse(
        Availability availability,
        Result result,
        Error error,
        boolean retryable,
        String traceId) {

    public RecoverySampleComputationResponse {
        boolean available = availability == Availability.AVAILABLE;
        if (availability == null || available != (result != null)
                || available != (error == null) || available && retryable
                || traceId == null || !traceId.matches("(?!0{32})[0-9a-f]{32}")) {
            throw new IllegalArgumentException("RECOVERY_SAMPLE_COMPUTATION_RESPONSE_INVALID");
        }
    }

    public static RecoverySampleComputationResponse available(Result result) {
        return new RecoverySampleComputationResponse(
                Availability.AVAILABLE, result, null, false, result.traceId());
    }

    public static RecoverySampleComputationResponse failure(
            Error error, boolean retryable, String traceId) {
        return new RecoverySampleComputationResponse(
                Availability.UNAVAILABLE, null, error, retryable, traceId);
    }

    public enum Availability { AVAILABLE, UNAVAILABLE }
    public enum Error {
        PROVIDER_UNAVAILABLE,
        UNKNOWN_VERSION_OR_DIGEST,
        REQUEST_CONFLICT,
        BOUNDS_EXCEEDED,
        TIMEOUT
    }

    public record Result(
            String providerVersion,
            String selectionSeed,
            long populationCount,
            int selectedCount,
            List<Stratum> strata,
            String strataSummaryDigest,
            String expectedDigest,
            String actualDigest,
            int mismatchCount,
            Instant completedAt,
            String traceId) {
        public Result {
            strata = List.copyOf(strata).stream()
                    .sorted(Comparator.comparing(Stratum::stratumCode)).toList();
        }
    }

    public record Stratum(
            String stratumCode,
            long populationCount,
            int selectedCount,
            int mismatchCount,
            String summaryDigest) {}
}
