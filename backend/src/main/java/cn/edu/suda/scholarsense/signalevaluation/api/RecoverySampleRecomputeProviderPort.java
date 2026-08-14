package cn.edu.suda.scholarsense.signalevaluation.api;

/** Public signal-evaluation pure bounded computation boundary. */
@FunctionalInterface
public interface RecoverySampleRecomputeProviderPort {
    String PROVIDER_VERSION = "RECOVERY-SAMPLE-PROVIDER-1.0.0";
    int TIMEOUT_MILLIS = 30_000;
    int MAXIMUM_SELECTED_SUBJECT_WINDOWS = 10_000;
    int MAXIMUM_STRATA = 128;
    int MAXIMUM_WIRE_BYTES = 65_536;

    RecoverySampleRecomputeResponse recompute(RecoverySampleRecomputeRequest request);

    static RecoverySampleRecomputeProviderPort notInstalled() {
        return request -> RecoverySampleRecomputeResponse.failure(
                RecoverySampleProviderAvailability.NOT_INSTALLED,
                RecoverySampleProviderError.PROVIDER_NOT_INSTALLED,
                false,
                request.traceId());
    }
}
