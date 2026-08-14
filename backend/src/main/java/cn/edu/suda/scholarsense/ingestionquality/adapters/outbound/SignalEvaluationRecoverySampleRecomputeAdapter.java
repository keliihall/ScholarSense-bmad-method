package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.RecoverySampleRecomputeCommand;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoverySampleRecomputeOutcome;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoverySampleRecomputePort;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleProviderAvailability;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleProviderError;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleRecomputeProviderPort;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleRecomputeRequest;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleRecomputeResponse;
import java.util.Objects;

/** IQ-owned adapter imports only the signal-evaluation public API. */
public final class SignalEvaluationRecoverySampleRecomputeAdapter
        implements RecoverySampleRecomputePort {
    private final RecoverySampleRecomputeProviderPort provider;

    public SignalEvaluationRecoverySampleRecomputeAdapter(
            RecoverySampleRecomputeProviderPort provider) {
        this.provider = Objects.requireNonNull(provider);
    }

    @Override
    public RecoverySampleRecomputeOutcome recompute(RecoverySampleRecomputeCommand command) {
        RecoverySampleRecomputeResponse response = provider.recompute(
                new RecoverySampleRecomputeRequest(
                        command.providerVersion(), command.recoveryRequestId(), command.episodeId(),
                        command.taskId(), command.ruleVersionsDigest(), command.memberSetDigest(),
                        command.watermarksDigest(), command.qualityRecoveryPolicyVersion(),
                        command.qualityRecoveryPolicyDigest(),
                        command.opaqueSubjectWindowSelectionRef(), command.selectionSeed(),
                        command.requestedSampleCount(), command.requestDigest(), command.traceId()));
        RecoverySampleRecomputeOutcome.BoundResult result = response.result() == null ? null
                : new RecoverySampleRecomputeOutcome.BoundResult(
                    new RecoverySampleRecomputeOutcome.InputBinding(
                        command.requestDigest(), command.ruleVersionsDigest(),
                        command.memberSetDigest(), command.watermarksDigest(),
                        command.qualityRecoveryPolicyDigest(), command.selectionSeed()),
                    new RecoverySampleRecomputeOutcome.Summary(
                        response.result().providerVersion(), response.result().selectionSeed(),
                        response.result().populationCount(), response.result().selectedCount(),
                        response.result().strata().stream()
                                .map(value -> new RecoverySampleRecomputeOutcome.StratumSummary(
                                        value.stratumCode(), value.populationCount(),
                                        value.selectedCount(), value.mismatchCount(),
                                        value.summaryDigest()))
                                .toList(),
                        response.result().strataSummaryDigest(), response.result().expectedDigest(),
                        response.result().actualDigest(), response.result().mismatchCount(),
                        response.result().completedAt()));
        return new RecoverySampleRecomputeOutcome(
                availability(response.availability()), result, error(response.errorCode()),
                response.retryable(), response.traceId());
    }

    private static RecoverySampleRecomputeOutcome.Availability availability(
            RecoverySampleProviderAvailability value) {
        return switch (value) {
            case AVAILABLE -> RecoverySampleRecomputeOutcome.Availability.AVAILABLE;
            case UNAVAILABLE -> RecoverySampleRecomputeOutcome.Availability.UNAVAILABLE;
            case NOT_INSTALLED -> RecoverySampleRecomputeOutcome.Availability.NOT_INSTALLED;
        };
    }

    private static RecoverySampleRecomputeOutcome.ErrorCode error(
            RecoverySampleProviderError value) {
        if (value == null) return null;
        return switch (value) {
            case PROVIDER_UNAVAILABLE -> RecoverySampleRecomputeOutcome.ErrorCode.DEPENDENCY_UNAVAILABLE;
            case PROVIDER_NOT_INSTALLED -> RecoverySampleRecomputeOutcome.ErrorCode.PROVIDER_NOT_INSTALLED;
            case UNKNOWN_VERSION_OR_DIGEST -> RecoverySampleRecomputeOutcome.ErrorCode.UNKNOWN_VERSION_OR_DIGEST;
            case REQUEST_CONFLICT -> RecoverySampleRecomputeOutcome.ErrorCode.REQUEST_CONFLICT;
            case BOUNDS_EXCEEDED -> RecoverySampleRecomputeOutcome.ErrorCode.BOUNDS_EXCEEDED;
            case TIMEOUT -> RecoverySampleRecomputeOutcome.ErrorCode.TIMEOUT;
        };
    }
}
