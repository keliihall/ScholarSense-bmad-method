package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.edu.suda.scholarsense.ingestionquality.application.RecoverySampleRecomputeCommand;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoverySampleRecomputeOutcome;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoverySampleRecomputePort;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleProviderAvailability;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleProviderError;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleRecomputeProviderPort;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleRecomputeResponse;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleRecomputeResult;
import cn.edu.suda.scholarsense.signalevaluation.api.RecoverySampleStratumSummary;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SignalEvaluationRecoverySampleRecomputeAdapterTest {

    private static final String A = "sha256:" + "a".repeat(64);
    private static final String B = "sha256:" + "b".repeat(64);
    private static final String C = "sha256:" + "c".repeat(64);
    private static final String D = "sha256:" + "d".repeat(64);
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final Instant AT = Instant.parse("2026-08-12T11:12:13.123456Z");

    @Test
    void mapsOnlyThePublicSignalEvaluationApiIntoTheOwnerInternalPort() {
        RecoverySampleRecomputeProviderPort provider = request ->
                RecoverySampleRecomputeResponse.available(new RecoverySampleRecomputeResult(
                        request.providerVersion(), request.selectionSeed(), 100, 100,
                        List.of(new RecoverySampleStratumSummary(
                                "ALL", 100, 100, 0, C)),
                        RecoverySampleRecomputeResult.canonicalStrataDigest(List.of(
                                new RecoverySampleStratumSummary(
                                        "ALL", 100, 100, 0, C))),
                        B, B, 0, AT, request.traceId()));
        RecoverySampleRecomputePort adapter =
                new SignalEvaluationRecoverySampleRecomputeAdapter(provider);

        RecoverySampleRecomputeOutcome outcome = adapter.recompute(command());

        assertEquals(RecoverySampleRecomputeOutcome.Availability.AVAILABLE,
                outcome.availability());
        assertEquals(D, outcome.result().binding().requestDigest());
        assertEquals(A, outcome.result().binding().ruleVersionsDigest());
        assertEquals(B, outcome.result().binding().memberSetDigest());
        assertEquals(C, outcome.result().binding().watermarksDigest());
        assertEquals(100, outcome.result().summary().selectedCount());
        assertEquals(0, outcome.result().summary().mismatchCount());
        assertEquals(RecoverySampleRecomputeOutcome.Summary.canonicalStrataDigest(
                        outcome.result().summary().strata()),
                outcome.result().summary().strataSummaryDigest());
        assertEquals(List.of("ALL"), outcome.result().summary().strata().stream()
                .map(RecoverySampleRecomputeOutcome.StratumSummary::stratumCode).toList());
        assertNull(outcome.errorCode());
    }

    @Test
    void preservesUnavailableAndNotInstalledAsClosedOwnerFailures() {
        RecoverySampleRecomputePort unavailable = new SignalEvaluationRecoverySampleRecomputeAdapter(
                request -> RecoverySampleRecomputeResponse.failure(
                        RecoverySampleProviderAvailability.UNAVAILABLE,
                        RecoverySampleProviderError.PROVIDER_UNAVAILABLE, true, request.traceId()));
        RecoverySampleRecomputeOutcome transientFailure = unavailable.recompute(command());
        assertEquals(RecoverySampleRecomputeOutcome.Availability.UNAVAILABLE,
                transientFailure.availability());
        assertEquals(RecoverySampleRecomputeOutcome.ErrorCode.DEPENDENCY_UNAVAILABLE,
                transientFailure.errorCode());
        assertEquals(true, transientFailure.retryable());

        RecoverySampleRecomputePort missing = new SignalEvaluationRecoverySampleRecomputeAdapter(
                RecoverySampleRecomputeProviderPort.notInstalled());
        RecoverySampleRecomputeOutcome permanentFailure = missing.recompute(command());
        assertEquals(RecoverySampleRecomputeOutcome.Availability.NOT_INSTALLED,
                permanentFailure.availability());
        assertEquals(RecoverySampleRecomputeOutcome.ErrorCode.PROVIDER_NOT_INSTALLED,
                permanentFailure.errorCode());
        assertEquals(false, permanentFailure.retryable());
    }

    private static RecoverySampleRecomputeCommand command() {
        return new RecoverySampleRecomputeCommand(
                RecoverySampleRecomputeProviderPort.PROVIDER_VERSION,
                "018f0f3e-7b2a-7cc1-8a10-111111111111",
                "018f0f3e-7b2a-7cc1-8a10-222222222222",
                "018f0f3e-7b2a-7cc1-8a10-333333333333",
                A, B, C, "QRP-1.0.0",
                "sha256:195a22553b13ac35da5923e704ef8385f85d17574b8a753cc1afc16c2055f366",
                "oswref:v1:" + "1".repeat(64), D, 100, D, TRACE);
    }
}
