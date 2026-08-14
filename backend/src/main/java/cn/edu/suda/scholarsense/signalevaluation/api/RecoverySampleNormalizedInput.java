package cn.edu.suda.scholarsense.signalevaluation.api;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Bounded, digest-only sealed input owned by signal-evaluation. */
public record RecoverySampleNormalizedInput(
        String opaqueSelectionRef,
        long inputVersion,
        String ruleVersionsDigest,
        String memberSetDigest,
        String watermarksDigest,
        String qualityRecoveryPolicyDigest,
        String selectionSeed,
        List<Stratum> strata,
        String strataDigest,
        Instant sealedAt,
        Instant effectiveAt,
        Instant expiresAt) {

    public RecoverySampleNormalizedInput {
        strata = List.copyOf(strata).stream()
                .sorted(Comparator.comparing(Stratum::code)).toList();
        if (opaqueSelectionRef == null
                || !opaqueSelectionRef.matches("oswref:v1:[0-9a-f]{64}")
                || inputVersion < 1 || !digest(ruleVersionsDigest)
                || !digest(memberSetDigest) || !digest(watermarksDigest)
                || !RecoverySampleRecomputeRequest.QUALITY_RECOVERY_POLICY_DIGEST
                        .equals(qualityRecoveryPolicyDigest)
                || !digest(selectionSeed) || !digest(strataDigest)
                || strata.isEmpty() || strata.size() > 128
                || strata.stream().map(Stratum::code).distinct().count() != strata.size()
                || sealedAt == null || effectiveAt == null || expiresAt == null
                || sealedAt.getNano() % 1_000 != 0 || effectiveAt.getNano() % 1_000 != 0
                || expiresAt.getNano() % 1_000 != 0
                || effectiveAt.isBefore(sealedAt) || !expiresAt.isAfter(effectiveAt)
                || strata.stream().mapToInt(value -> value.windows().size()).sum() > 10_000) {
            throw invalid();
        }
    }

    public record Stratum(String code, long populationCount, List<Window> windows) {
        public Stratum {
            windows = List.copyOf(windows).stream()
                    .sorted(Comparator.comparing(Window::selectionRankDigest)).toList();
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}")
                    || populationCount < 0 || windows.size() > populationCount
                    || windows.size() > 10_000
                    || windows.stream().map(Window::selectionRankDigest).distinct().count()
                            != windows.size()) {
                throw invalid();
            }
        }
    }

    public record Window(
            String selectionRankDigest,
            String expectedDigest,
            String normalizedInputDigest) {
        public Window {
            if (!digest(selectionRankDigest) || !digest(expectedDigest)
                    || !digest(normalizedInputDigest)) throw invalid();
        }
    }

    private static boolean digest(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("RECOVERY_SAMPLE_NORMALIZED_INPUT_INVALID");
    }
}
