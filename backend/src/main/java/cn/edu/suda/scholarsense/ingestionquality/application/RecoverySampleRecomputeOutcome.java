package cn.edu.suda.scholarsense.ingestionquality.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** IQ-owned immutable, input-bound projection of the public sample provider response. */
public record RecoverySampleRecomputeOutcome(
        Availability availability,
        BoundResult result,
        ErrorCode errorCode,
        boolean retryable,
        String traceId) {

    public RecoverySampleRecomputeOutcome {
        boolean available = availability == Availability.AVAILABLE;
        if (availability == null || available != (result != null)
                || available != (errorCode == null) || available && retryable
                || !RecoveryValidationPortRules.trace(traceId)) {
            throw new IllegalArgumentException("RECOVERY_SAMPLE_OUTCOME_INVALID");
        }
    }

    public enum Availability { AVAILABLE, UNAVAILABLE, NOT_INSTALLED }
    public enum ErrorCode {
        DEPENDENCY_UNAVAILABLE,
        PROVIDER_NOT_INSTALLED,
        UNKNOWN_VERSION_OR_DIGEST,
        REQUEST_CONFLICT,
        BOUNDS_EXCEEDED,
        TIMEOUT
    }

    public record InputBinding(
            String requestDigest,
            String ruleVersionsDigest,
            String memberSetDigest,
            String watermarksDigest,
            String qualityRecoveryPolicyDigest,
            String selectionSeed) {
        public InputBinding {
            for (String digest : List.of(requestDigest, ruleVersionsDigest, memberSetDigest,
                    watermarksDigest, qualityRecoveryPolicyDigest, selectionSeed)) {
                if (!RecoveryValidationPortRules.digest(digest)) {
                    throw new IllegalArgumentException("RECOVERY_SAMPLE_BINDING_INVALID");
                }
            }
        }
    }

    public record BoundResult(InputBinding binding, Summary summary) {
        public BoundResult {
            if (binding == null || summary == null
                    || !binding.selectionSeed().equals(summary.selectionSeed())) {
                throw new IllegalArgumentException("RECOVERY_SAMPLE_BOUND_RESULT_INVALID");
            }
        }
    }

    public record Summary(
            String providerVersion,
            String selectionSeed,
            long populationCount,
            int selectedCount,
            List<StratumSummary> strata,
            String strataSummaryDigest,
            String expectedDigest,
            String actualDigest,
            int mismatchCount,
            Instant completedAt) {
        public Summary {
            strata = List.copyOf(strata == null ? List.of() : strata).stream()
                    .sorted(Comparator.comparing(StratumSummary::stratumCode)).toList();
            if (!"RECOVERY-SAMPLE-PROVIDER-1.0.0".equals(providerVersion)
                    || !RecoveryValidationPortRules.digest(selectionSeed)
                    || !RecoveryValidationPortRules.digest(strataSummaryDigest)
                    || !RecoveryValidationPortRules.digest(expectedDigest)
                    || !RecoveryValidationPortRules.digest(actualDigest)
                    || populationCount < 0 || populationCount > 9_007_199_254_740_991L
                    || selectedCount < 0 || selectedCount > 10_000
                    || selectedCount > populationCount || mismatchCount < 0
                    || mismatchCount > selectedCount || strata.isEmpty() || strata.size() > 128
                    || strata.stream().map(StratumSummary::stratumCode).distinct().count()
                            != strata.size()
                    || !totalsMatch(strata, populationCount, selectedCount, mismatchCount)
                    || !strataSummaryDigest.equals(canonicalStrataDigest(strata))
                    || completedAt == null || completedAt.getNano() % 1_000 != 0) {
                throw new IllegalArgumentException("RECOVERY_SAMPLE_SUMMARY_INVALID");
            }
        }

        private static boolean totalsMatch(
                List<StratumSummary> values, long population, int selected, int mismatches) {
            try {
                long p = 0;
                int s = 0, m = 0;
                for (StratumSummary value : values) {
                    p = Math.addExact(p, value.populationCount());
                    s = Math.addExact(s, value.selectedCount());
                    m = Math.addExact(m, value.mismatchCount());
                }
                return p == population && s == selected && m == mismatches;
            } catch (ArithmeticException overflow) {
                return false;
            }
        }

        public static String canonicalStrataDigest(List<StratumSummary> values) {
            String canonical = List.copyOf(values).stream()
                    .sorted(Comparator.comparing(StratumSummary::stratumCode))
                    .map(value -> String.join("\n", value.stratumCode(),
                            Long.toString(value.populationCount()),
                            Integer.toString(value.selectedCount()),
                            Integer.toString(value.mismatchCount()), value.summaryDigest()))
                    .collect(java.util.stream.Collectors.joining("\n--\n"));
            try {
                byte[] bytes = MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(StandardCharsets.UTF_8));
                return "sha256:" + HexFormat.of().formatHex(bytes);
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
            }
        }
    }

    public record StratumSummary(
            String stratumCode,
            long populationCount,
            int selectedCount,
            int mismatchCount,
            String summaryDigest) {
        public StratumSummary {
            if (stratumCode == null || !stratumCode.matches("[A-Z][A-Z0-9_]{1,63}")
                    || populationCount < 0 || selectedCount < 0 || mismatchCount < 0
                    || selectedCount > populationCount || selectedCount > 10_000
                    || mismatchCount > selectedCount
                    || !RecoveryValidationPortRules.digest(summaryDigest)) {
                throw new IllegalArgumentException("RECOVERY_SAMPLE_STRATUM_INVALID");
            }
        }
    }
}
