package cn.edu.suda.scholarsense.signalevaluation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleNormalizedInputResolution;
import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleNormalizedInputResolverPort;
import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleProviderTimePort;
import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleRecomputeUseCase;
import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleReplayEntry;
import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleReplayStorePort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RecoverySampleRecomputeProviderTest {

    private static final String DIGEST_A = "sha256:" + "a".repeat(64);
    private static final String DIGEST_B = "sha256:" + "b".repeat(64);
    private static final String DIGEST_C = "sha256:" + "c".repeat(64);
    private static final String DIGEST_D = "sha256:" + "d".repeat(64);
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-12T11:12:13.123456Z");

    @Test
    void computesDeterministicPiiFreeSummaryAndReplaysTheSameImmutableResult() {
        AtomicInteger resolutions = new AtomicInteger();
        RecoverySampleNormalizedInputResolverPort resolver = command -> {
            resolutions.incrementAndGet();
            return resolved(command.selectionSeed(), List.of(
                    stratum("BETA", 40, 40, 40),
                    stratum("ALPHA", 60, 60, 60)));
        };
        var provider = provider(resolver, new TickingTime(0, 1, COMPLETED_AT));
        var request = request("oswref:v1:" + "1".repeat(64), TRACE, 100);

        RecoverySampleRecomputeResponse first = provider.recompute(request);
        RecoverySampleRecomputeResponse replay = provider.recompute(request);

        assertEquals(RecoverySampleProviderAvailability.AVAILABLE, first.availability());
        assertNull(first.errorCode());
        assertEquals(100, first.result().populationCount());
        assertEquals(100, first.result().selectedCount());
        assertEquals(0, first.result().mismatchCount());
        assertEquals(List.of("ALPHA", "BETA"), first.result().strata().stream()
                .map(RecoverySampleStratumSummary::stratumCode).toList());
        assertEquals(100, first.result().strata().stream()
                .mapToLong(RecoverySampleStratumSummary::populationCount).sum());
        assertEquals(COMPLETED_AT, first.result().completedAt());
        assertEquals(first.result(), replay.result());
        assertEquals(1, resolutions.get(), "same providerVersion+requestDigest is computed once");

        var reverseProvider = provider(command -> resolved(
                command.selectionSeed(), List.of(
                        stratum("ALPHA", 60, 60, 60),
                        stratum("BETA", 40, 40, 40))),
                new TickingTime(0, 1, COMPLETED_AT));
        RecoverySampleRecomputeResult reverse = reverseProvider.recompute(request).result();
        assertEquals(first.result().strataSummaryDigest(), reverse.strataSummaryDigest());
        assertEquals(first.result().expectedDigest(), reverse.expectedDigest());
        assertEquals(first.result().actualDigest(), reverse.actualDigest());
    }

    @Test
    void sameIdempotencyDigestWithDifferentBodyIsRejectedWithoutRecomputing() {
        AtomicInteger resolutions = new AtomicInteger();
        var provider = provider(command -> {
            resolutions.incrementAndGet();
            return resolved(command.selectionSeed(), List.of(stratum("ONLY", 1, 1, 1)));
        }, new TickingTime(0, 1, COMPLETED_AT));
        var original = request("oswref:v1:" + "1".repeat(64), TRACE, 1);
        var collision = rawRequest(
                original.requestDigest(), "oswref:v1:" + "2".repeat(64), TRACE, 1);

        assertEquals(RecoverySampleProviderAvailability.AVAILABLE,
                provider.recompute(original).availability());
        RecoverySampleRecomputeResponse rejected = provider.recompute(collision);

        assertEquals(RecoverySampleProviderAvailability.UNAVAILABLE, rejected.availability());
        assertEquals(RecoverySampleProviderError.REQUEST_CONFLICT, rejected.errorCode());
        assertEquals(false, rejected.retryable());
        assertEquals(1, resolutions.get());
    }

    @Test
    void durableReplaySurvivesAProviderRestartAndForgedRequestDigestFailsClosed() {
        InMemoryReplayStore durable = new InMemoryReplayStore();
        AtomicInteger resolutions = new AtomicInteger();
        RecoverySampleNormalizedInputResolverPort resolver = command -> {
            resolutions.incrementAndGet();
            return resolved(command.selectionSeed(), List.of(stratum("ALL", 1, 1, 1)));
        };
        RecoverySampleRecomputeRequest request = request(
                "oswref:v1:" + "1".repeat(64), TRACE, 1);
        var first = new RecoverySampleRecomputeProvider(
                new RecoverySampleRecomputeUseCase(
                        resolver, new TickingTime(0, 1, COMPLETED_AT), durable));
        RecoverySampleRecomputeResponse stored = first.recompute(request);
        var restarted = new RecoverySampleRecomputeProvider(
                new RecoverySampleRecomputeUseCase(
                        resolver, new TickingTime(0, 1, COMPLETED_AT.plusSeconds(30)), durable));

        assertEquals(stored.result(), restarted.recompute(request).result());
        assertEquals(1, resolutions.get());

        RecoverySampleRecomputeRequest forged = rawRequest(
                DIGEST_A, "oswref:v1:" + "3".repeat(64), TRACE, 1);
        RecoverySampleRecomputeResponse rejected = restarted.recompute(forged);
        assertEquals(RecoverySampleProviderError.UNKNOWN_VERSION_OR_DIGEST,
                rejected.errorCode());
        assertEquals(1, resolutions.get());
    }

    @Test
    void unavailableUnknownBindingsTimeoutAndNotInstalledFailClosed() {
        var unavailable = provider(
                command -> new RecoverySampleNormalizedInputResolution.Unavailable(),
                new TickingTime(0, 1, COMPLETED_AT)).recompute(request());
        assertEquals(RecoverySampleProviderAvailability.UNAVAILABLE, unavailable.availability());
        assertEquals(RecoverySampleProviderError.PROVIDER_UNAVAILABLE, unavailable.errorCode());
        assertEquals(true, unavailable.retryable());

        var unknown = provider(
                command -> new RecoverySampleNormalizedInputResolution.UnknownBindings(),
                new TickingTime(0, 1, COMPLETED_AT)).recompute(request());
        assertEquals(RecoverySampleProviderError.UNKNOWN_VERSION_OR_DIGEST, unknown.errorCode());
        assertEquals(false, unknown.retryable());

        var timedOut = provider(
                command -> resolved(command.selectionSeed(), List.of(stratum("ONLY", 1, 1, 1))),
                new TickingTime(0, 30_000_000_001L, COMPLETED_AT)).recompute(
                        request("oswref:v1:" + "1".repeat(64), TRACE, 1));
        assertEquals(RecoverySampleProviderError.TIMEOUT, timedOut.errorCode());
        assertEquals(true, timedOut.retryable());

        RecoverySampleRecomputeResponse notInstalled =
                RecoverySampleRecomputeProviderPort.notInstalled().recompute(request());
        assertEquals(RecoverySampleProviderAvailability.NOT_INSTALLED, notInstalled.availability());
        assertEquals(RecoverySampleProviderError.PROVIDER_NOT_INSTALLED, notInstalled.errorCode());
        assertNull(notInstalled.result());
    }

    @Test
    void exactProviderPolicyAndWireBoundsAreClosed() {
        RecoverySampleRecomputeRequest base = request();
        assertEquals(30_000, RecoverySampleRecomputeProviderPort.TIMEOUT_MILLIS);
        assertEquals(10_000, RecoverySampleRecomputeProviderPort.MAXIMUM_SELECTED_SUBJECT_WINDOWS);
        assertEquals(128, RecoverySampleRecomputeProviderPort.MAXIMUM_STRATA);
        assertEquals(65_536, RecoverySampleRecomputeProviderPort.MAXIMUM_WIRE_BYTES);
        assertThrows(IllegalArgumentException.class, () -> new RecoverySampleRecomputeRequest(
                "RECOVERY-SAMPLE-PROVIDER-9.0.0", base.recoveryRequestId(), base.episodeId(),
                base.taskId(), base.ruleVersionsDigest(), base.memberSetDigest(),
                base.watermarksDigest(), base.qualityRecoveryPolicyVersion(),
                base.qualityRecoveryPolicyDigest(), base.opaqueSubjectWindowSelectionRef(),
                base.selectionSeed(), base.requestedSampleCount(), base.requestDigest(), base.traceId()));
        assertThrows(IllegalArgumentException.class, () -> new RecoverySampleRecomputeRequest(
                base.providerVersion(), base.recoveryRequestId(), base.episodeId(), base.taskId(),
                base.ruleVersionsDigest(), base.memberSetDigest(), base.watermarksDigest(),
                base.qualityRecoveryPolicyVersion(), DIGEST_A,
                base.opaqueSubjectWindowSelectionRef(), base.selectionSeed(),
                base.requestedSampleCount(), base.requestDigest(), base.traceId()));
        assertThrows(IllegalArgumentException.class, () -> new RecoverySampleRecomputeRequest(
                base.providerVersion(), base.recoveryRequestId(), base.episodeId(), base.taskId(),
                base.ruleVersionsDigest(), base.memberSetDigest(), base.watermarksDigest(),
                base.qualityRecoveryPolicyVersion(), base.qualityRecoveryPolicyDigest(),
                base.opaqueSubjectWindowSelectionRef(), base.selectionSeed(), 10_001,
                base.requestDigest(), base.traceId()));
        assertThrows(IllegalArgumentException.class, () -> new RecoverySampleRecomputeRequest(
                base.providerVersion(), base.recoveryRequestId(), base.episodeId(), base.taskId(),
                base.ruleVersionsDigest(), base.memberSetDigest(), base.watermarksDigest(),
                base.qualityRecoveryPolicyVersion(), base.qualityRecoveryPolicyDigest(),
                "oswref:v1:" + "1".repeat(65_536), base.selectionSeed(), 100,
                base.requestDigest(), base.traceId()));
    }

    @Test
    void resolvedInputMustSelectAllIfFewerAndStayWithinStrataAndSelectionBounds() {
        var shortSelection = provider(command -> resolved(
                command.selectionSeed(), List.of(stratum("ONLY", 99, 98, 98))),
                new TickingTime(0, 1, COMPLETED_AT)).recompute(
                        request("oswref:v1:" + "1".repeat(64), TRACE, 100));
        assertEquals(RecoverySampleProviderError.BOUNDS_EXCEEDED, shortSelection.errorCode());

        List<RecoverySampleNormalizedInputResolution.NormalizedStratum> tooMany =
                new ArrayList<>();
        for (int index = 0; index < 129; index++) {
            tooMany.add(stratum("S" + index, 1, 1, 1));
        }
        var tooManyStrata = provider(command -> resolved(command.selectionSeed(), tooMany),
                new TickingTime(0, 1, COMPLETED_AT)).recompute(
                        request("oswref:v1:" + "1".repeat(64), TRACE, 129));
        assertEquals(RecoverySampleProviderError.BOUNDS_EXCEEDED, tooManyStrata.errorCode());
    }

    private static RecoverySampleRecomputeProviderPort provider(
            RecoverySampleNormalizedInputResolverPort resolver,
            RecoverySampleProviderTimePort time) {
        return new RecoverySampleRecomputeProvider(
                new RecoverySampleRecomputeUseCase(resolver, time, new InMemoryReplayStore()));
    }

    private static RecoverySampleNormalizedInputResolution.Resolved resolved(
            String selectionSeed,
            List<RecoverySampleNormalizedInputResolution.NormalizedStratum> strata) {
        return new RecoverySampleNormalizedInputResolution.Resolved(
                DIGEST_A, DIGEST_B, DIGEST_C,
                RecoverySampleRecomputeRequest.QUALITY_RECOVERY_POLICY_DIGEST,
                selectionSeed, strata);
    }

    private static RecoverySampleNormalizedInputResolution.NormalizedStratum stratum(
            String code, long population, int selected, int matching) {
        List<RecoverySampleNormalizedInputResolution.NormalizedSubjectWindow> windows =
                new ArrayList<>();
        for (int index = 0; index < selected; index++) {
            String rank = "sha256:" + "%064x".formatted(index + 1L);
            String normalized = "sha256:" + "%064x".formatted(index + 1001L);
            String recomputed = digest(
                    "RECOVERY-NORMALIZED-WINDOW-1.0.0\n" + normalized);
            String expected = index < matching ? recomputed
                    : "sha256:" + "%064x".formatted(index + 101L);
            windows.add(new RecoverySampleNormalizedInputResolution.NormalizedSubjectWindow(
                    rank, expected, normalized));
        }
        return new RecoverySampleNormalizedInputResolution.NormalizedStratum(
                code, population, windows);
    }

    private static RecoverySampleRecomputeRequest request() {
        return request("oswref:v1:" + "1".repeat(64), TRACE, 100);
    }

    private static RecoverySampleRecomputeRequest request(
            String selectionRef, String traceId, int requestedCount) {
        RecoverySampleRecomputeRequest provisional = rawRequest(
                DIGEST_D, selectionRef, traceId, requestedCount);
        return rawRequest(
                provisional.canonicalRequestDigest(), selectionRef, traceId, requestedCount);
    }

    private static RecoverySampleRecomputeRequest rawRequest(
            String requestDigest, String selectionRef, String traceId, int requestedCount) {
        return new RecoverySampleRecomputeRequest(
                RecoverySampleRecomputeProviderPort.PROVIDER_VERSION,
                "018f0f3e-7b2a-7cc1-8a10-111111111111",
                "018f0f3e-7b2a-7cc1-8a10-222222222222",
                "018f0f3e-7b2a-7cc1-8a10-333333333333",
                DIGEST_A, DIGEST_B, DIGEST_C,
                RecoverySampleRecomputeRequest.QUALITY_RECOVERY_POLICY_VERSION,
                RecoverySampleRecomputeRequest.QUALITY_RECOVERY_POLICY_DIGEST,
                selectionRef, DIGEST_D, requestedCount, requestDigest, traceId);
    }

    private static String digest(String value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static final class InMemoryReplayStore implements RecoverySampleReplayStorePort {
        private final Map<String, RecoverySampleReplayEntry> entries = new HashMap<>();

        @Override
        public Optional<RecoverySampleReplayEntry> find(
                String providerVersion, String requestDigest) {
            return Optional.ofNullable(entries.get(providerVersion + "\n" + requestDigest));
        }

        @Override
        public RecoverySampleReplayEntry insertIfAbsent(RecoverySampleReplayEntry requested) {
            String key = requested.providerVersion() + "\n" + requested.requestDigest();
            return entries.computeIfAbsent(key, ignored -> requested);
        }
    }

    private static final class TickingTime implements RecoverySampleProviderTimePort {
        private final long first;
        private final long second;
        private final Instant now;
        private int calls;

        private TickingTime(long first, long second, Instant now) {
            this.first = first;
            this.second = second;
            this.now = now;
        }

        @Override
        public long monotonicNanos() {
            return calls++ == 0 ? first : second;
        }

        @Override
        public Instant trustedNow() {
            return now;
        }
    }
}
