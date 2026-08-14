package cn.edu.suda.scholarsense.signalevaluation.api;

/** Closed success/failure envelope; unavailable can never masquerade as mismatch zero. */
public record RecoverySampleRecomputeResponse(
        RecoverySampleProviderAvailability availability,
        RecoverySampleRecomputeResult result,
        RecoverySampleProviderError errorCode,
        boolean retryable,
        String traceId) {

    public RecoverySampleRecomputeResponse {
        boolean available = availability == RecoverySampleProviderAvailability.AVAILABLE;
        if (availability == null
                || available != (result != null)
                || available != (errorCode == null)
                || available && retryable
                || traceId == null
                || !traceId.matches("(?!0{32})[0-9a-f]{32}")
                || result != null && !traceId.equals(result.traceId())
                || availability == RecoverySampleProviderAvailability.NOT_INSTALLED
                        && errorCode != RecoverySampleProviderError.PROVIDER_NOT_INSTALLED) {
            throw new IllegalArgumentException("RECOVERY_SAMPLE_RESPONSE_INVALID");
        }
    }

    public static RecoverySampleRecomputeResponse available(
            RecoverySampleRecomputeResult result) {
        return new RecoverySampleRecomputeResponse(
                RecoverySampleProviderAvailability.AVAILABLE, result, null, false,
                result.traceId());
    }

    public static RecoverySampleRecomputeResponse failure(
            RecoverySampleProviderAvailability availability,
            RecoverySampleProviderError error,
            boolean retryable,
            String traceId) {
        return new RecoverySampleRecomputeResponse(
                availability, null, error, retryable, traceId);
    }
}
