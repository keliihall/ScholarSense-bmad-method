package cn.edu.suda.scholarsense.signalevaluation.api;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Immutable counts/digests-only result. */
public record RecoverySampleRecomputeResult(
        String providerVersion,
        String selectionSeed,
        long populationCount,
        int selectedCount,
        List<RecoverySampleStratumSummary> strata,
        String strataSummaryDigest,
        String expectedDigest,
        String actualDigest,
        int mismatchCount,
        Instant completedAt,
        String traceId) {

    private static final String DIGEST = "sha256:[0-9a-f]{64}";

    public RecoverySampleRecomputeResult {
        strata = List.copyOf(strata == null ? List.of() : strata).stream()
                .sorted(Comparator.comparing(RecoverySampleStratumSummary::stratumCode))
                .toList();
        if (!RecoverySampleRecomputeProviderPort.PROVIDER_VERSION.equals(providerVersion)
                || !matches(selectionSeed)
                || populationCount < 0
                || populationCount > 9_007_199_254_740_991L
                || selectedCount < 0
                || selectedCount > RecoverySampleRecomputeProviderPort.MAXIMUM_SELECTED_SUBJECT_WINDOWS
                || selectedCount > populationCount
                || strata.isEmpty()
                || strata.size() > RecoverySampleRecomputeProviderPort.MAXIMUM_STRATA
                || strata.stream().anyMatch(value -> value == null)
                || strata.stream().map(RecoverySampleStratumSummary::stratumCode).distinct().count()
                        != strata.size()
                || !totalsMatch(strata, populationCount, selectedCount, mismatchCount)
                || !matches(strataSummaryDigest)
                || !strataSummaryDigest.equals(canonicalStrataDigest(strata))
                || !matches(expectedDigest)
                || !matches(actualDigest)
                || mismatchCount < 0
                || mismatchCount > selectedCount
                || completedAt == null
                || completedAt.getNano() % 1_000 != 0
                || traceId == null
                || !traceId.matches("(?!0{32})[0-9a-f]{32}")) {
            throw new IllegalArgumentException("RECOVERY_SAMPLE_RESULT_INVALID");
        }
    }

    private static boolean matches(String value) {
        return value != null && value.matches(DIGEST);
    }

    private static boolean totalsMatch(
            List<RecoverySampleStratumSummary> strata,
            long populationCount,
            int selectedCount,
            int mismatchCount) {
        try {
            long population = 0;
            int selected = 0;
            int mismatches = 0;
            for (RecoverySampleStratumSummary stratum : strata) {
                population = Math.addExact(population, stratum.populationCount());
                selected = Math.addExact(selected, stratum.selectedCount());
                mismatches = Math.addExact(mismatches, stratum.mismatchCount());
            }
            return population == populationCount
                    && selected == selectedCount
                    && mismatches == mismatchCount;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    public static String canonicalStrataDigest(List<RecoverySampleStratumSummary> values) {
        List<RecoverySampleStratumSummary> ordered = List.copyOf(values).stream()
                .sorted(Comparator.comparing(RecoverySampleStratumSummary::stratumCode)).toList();
        String canonical = ordered.stream().map(value -> String.join("\n",
                value.stratumCode(), Long.toString(value.populationCount()),
                Integer.toString(value.selectedCount()), Integer.toString(value.mismatchCount()),
                value.summaryDigest())).collect(java.util.stream.Collectors.joining("\n--\n"));
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
        }
    }
}
