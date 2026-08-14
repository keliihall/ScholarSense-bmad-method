package cn.edu.suda.scholarsense.signalevaluation.api;

import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleRecomputeUseCase;
import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleComputationRequest;
import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleComputationResponse;
import java.util.Objects;

/** Public facade keeps provider application types out of the consumer module. */
public final class RecoverySampleRecomputeProvider
        implements RecoverySampleRecomputeProviderPort {
    private final RecoverySampleRecomputeUseCase useCase;

    public RecoverySampleRecomputeProvider(RecoverySampleRecomputeUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase);
    }

    @Override
    public RecoverySampleRecomputeResponse recompute(RecoverySampleRecomputeRequest request) {
        RecoverySampleComputationResponse response = useCase.recompute(
                new RecoverySampleComputationRequest(
                        request.providerVersion(), request.recoveryRequestId(),
                        request.episodeId(), request.taskId(), request.ruleVersionsDigest(),
                        request.memberSetDigest(), request.watermarksDigest(),
                        request.qualityRecoveryPolicyVersion(),
                        request.qualityRecoveryPolicyDigest(),
                        request.opaqueSubjectWindowSelectionRef(), request.selectionSeed(),
                        request.requestedSampleCount(), request.requestDigest(), request.traceId()));
        if (response.availability()
                == RecoverySampleComputationResponse.Availability.AVAILABLE) {
            var value = response.result();
            return RecoverySampleRecomputeResponse.available(new RecoverySampleRecomputeResult(
                    value.providerVersion(), value.selectionSeed(), value.populationCount(),
                    value.selectedCount(), value.strata().stream()
                            .map(stratum -> new RecoverySampleStratumSummary(
                                    stratum.stratumCode(), stratum.populationCount(),
                                    stratum.selectedCount(), stratum.mismatchCount(),
                                    stratum.summaryDigest()))
                            .toList(), value.strataSummaryDigest(), value.expectedDigest(),
                    value.actualDigest(), value.mismatchCount(), value.completedAt(),
                    value.traceId()));
        }
        return RecoverySampleRecomputeResponse.failure(
                RecoverySampleProviderAvailability.UNAVAILABLE,
                switch (response.error()) {
                    case PROVIDER_UNAVAILABLE -> RecoverySampleProviderError.PROVIDER_UNAVAILABLE;
                    case UNKNOWN_VERSION_OR_DIGEST ->
                            RecoverySampleProviderError.UNKNOWN_VERSION_OR_DIGEST;
                    case REQUEST_CONFLICT -> RecoverySampleProviderError.REQUEST_CONFLICT;
                    case BOUNDS_EXCEEDED -> RecoverySampleProviderError.BOUNDS_EXCEEDED;
                    case TIMEOUT -> RecoverySampleProviderError.TIMEOUT;
                },
                response.retryable(), response.traceId());
    }
}
