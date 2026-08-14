package cn.edu.suda.scholarsense.signalevaluation.application;

import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleComputationResponse.Error;
import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleComputationResponse.Result;
import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleComputationResponse.Stratum;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Pure bounded sample evaluator backed by a durable provider-owned replay store. */
public final class RecoverySampleRecomputeUseCase {
    private final RecoverySampleNormalizedInputResolverPort resolver;
    private final RecoverySampleProviderTimePort time;
    private final RecoverySampleReplayStorePort replays;

    public RecoverySampleRecomputeUseCase(
            RecoverySampleNormalizedInputResolverPort resolver,
            RecoverySampleProviderTimePort time,
            RecoverySampleReplayStorePort replays) {
        this.resolver = Objects.requireNonNull(resolver);
        this.time = Objects.requireNonNull(time);
        this.replays = Objects.requireNonNull(replays);
    }

    public RecoverySampleComputationResponse recompute(RecoverySampleComputationRequest request) {
        Objects.requireNonNull(request);
        String bodyDigest = digest(request.canonicalBody());
        RecoverySampleReplayEntry prior = replays
                .find(request.providerVersion(), request.requestDigest()).orElse(null);
        if (prior != null) {
            return prior.canonicalBodyDigest().equals(bodyDigest)
                    ? prior.response()
                    : failure(Error.REQUEST_CONFLICT, false, request.traceId());
        }
        if (!request.requestDigest().equals(bodyDigest)) {
            return failure(
                    Error.UNKNOWN_VERSION_OR_DIGEST,
                    false,
                    request.traceId());
        }

        long started = time.monotonicNanos();
        RecoverySampleComputationResponse computed = compute(request);
        long elapsed = time.monotonicNanos() - started;
        if (elapsed < 0 || elapsed > RecoverySampleComputationRequest.TIMEOUT_NANOS) {
            computed = failure(Error.TIMEOUT, true, request.traceId());
        }
        RecoverySampleReplayEntry winner = replays.insertIfAbsent(new RecoverySampleReplayEntry(
                request.providerVersion(), request.requestDigest(), bodyDigest, computed));
        return winner.canonicalBodyDigest().equals(bodyDigest)
                ? winner.response()
                : failure(Error.REQUEST_CONFLICT, false, request.traceId());
    }

    private RecoverySampleComputationResponse compute(RecoverySampleComputationRequest request) {
        RecoverySampleNormalizedInputResolution resolution;
        try {
            resolution = resolver.resolve(RecoverySampleResolutionCommand.from(request));
        } catch (RuntimeException unavailable) {
            return failure(Error.PROVIDER_UNAVAILABLE, true, request.traceId());
        }
        if (resolution instanceof RecoverySampleNormalizedInputResolution.Unavailable) {
            return failure(Error.PROVIDER_UNAVAILABLE, true, request.traceId());
        }
        if (!(resolution instanceof RecoverySampleNormalizedInputResolution.Resolved value)
                || !value.ruleVersionsDigest().equals(request.ruleVersionsDigest())
                || !value.memberSetDigest().equals(request.memberSetDigest())
                || !value.watermarksDigest().equals(request.watermarksDigest())
                || !value.qualityRecoveryPolicyDigest().equals(request.qualityRecoveryPolicyDigest())
                || !value.selectionSeed().equals(request.selectionSeed())) {
            return failure(
                    Error.UNKNOWN_VERSION_OR_DIGEST, false, request.traceId());
        }
        if (value.strata().size() > RecoverySampleComputationRequest.MAXIMUM_STRATA) {
            return bounds(request.traceId());
        }
        long population = 0;
        int selected = 0;
        int mismatch = 0;
        List<Stratum> strata = new ArrayList<>();
        List<String> expectedBindings = new ArrayList<>();
        List<String> actualBindings = new ArrayList<>();
        for (var stratum : value.strata()) {
            try {
                population = Math.addExact(population, stratum.populationCount());
                selected = Math.addExact(selected, stratum.selectedWindows().size());
            } catch (ArithmeticException overflow) {
                return bounds(request.traceId());
            }
            int stratumMismatch = 0;
            for (var window : stratum.selectedWindows()) {
                String actualDigest = digest(
                        "RECOVERY-NORMALIZED-WINDOW-1.0.0\n"
                                + window.normalizedInputDigest());
                expectedBindings.add(stratum.code() + "\n" + window.selectionRankDigest()
                        + "\n" + window.expectedDigest());
                actualBindings.add(stratum.code() + "\n" + window.selectionRankDigest()
                        + "\n" + actualDigest);
                if (!window.expectedDigest().equals(actualDigest)) stratumMismatch++;
            }
            mismatch += stratumMismatch;
            String stratumBinding = stratum.code() + "\n" + stratum.populationCount() + "\n"
                    + stratum.selectedWindows().size() + "\n" + stratumMismatch;
            String stratumDigest = digest(stratumBinding);
            strata.add(new Stratum(
                    stratum.code(), stratum.populationCount(), stratum.selectedWindows().size(),
                    stratumMismatch, stratumDigest));
        }
        int required = population < request.requestedSampleCount()
                ? Math.toIntExact(population)
                : request.requestedSampleCount();
        if (population > 9_007_199_254_740_991L
                || selected > RecoverySampleComputationRequest.MAXIMUM_SELECTED_WINDOWS
                || selected < required) {
            return bounds(request.traceId());
        }
        Instant completedAt = time.trustedNow();
        if (completedAt == null || completedAt.getNano() % 1_000 != 0) {
            return failure(Error.PROVIDER_UNAVAILABLE, true, request.traceId());
        }
        return RecoverySampleComputationResponse.available(new Result(
                request.providerVersion(), request.selectionSeed(), population, selected,
                strata,
                canonicalStrataDigest(strata),
                digest(String.join("\n--\n", expectedBindings)),
                digest(String.join("\n--\n", actualBindings)),
                mismatch, completedAt, request.traceId()));
    }

    private static RecoverySampleComputationResponse bounds(String traceId) {
        return failure(Error.BOUNDS_EXCEEDED, false, traceId);
    }

    private static RecoverySampleComputationResponse failure(
            Error error, boolean retryable, String traceId) {
        return RecoverySampleComputationResponse.failure(error, retryable, traceId);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
        }
    }

    private static String canonicalStrataDigest(List<Stratum> values) {
        String canonical = values.stream()
                .sorted(java.util.Comparator.comparing(Stratum::stratumCode))
                .map(value -> String.join("\n", value.stratumCode(),
                        Long.toString(value.populationCount()),
                        Integer.toString(value.selectedCount()),
                        Integer.toString(value.mismatchCount()), value.summaryDigest()))
                .collect(java.util.stream.Collectors.joining("\n--\n"));
        return digest(canonical);
    }

}
