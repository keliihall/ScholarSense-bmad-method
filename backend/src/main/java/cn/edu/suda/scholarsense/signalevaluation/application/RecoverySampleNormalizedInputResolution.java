package cn.edu.suda.scholarsense.signalevaluation.application;

import java.util.Comparator;
import java.util.List;

/** Sealed provider-side resolution; opaque selection references never cross back to the consumer. */
public sealed interface RecoverySampleNormalizedInputResolution {

    record Resolved(
            String ruleVersionsDigest,
            String memberSetDigest,
            String watermarksDigest,
            String qualityRecoveryPolicyDigest,
            String selectionSeed,
            List<NormalizedStratum> strata)
            implements RecoverySampleNormalizedInputResolution {
        public Resolved {
            if (!digest(ruleVersionsDigest) || !digest(memberSetDigest)
                    || !digest(watermarksDigest) || !digest(qualityRecoveryPolicyDigest)
                    || !digest(selectionSeed) || strata == null) {
                throw invalid();
            }
            strata = strata.stream()
                    .sorted(Comparator.comparing(NormalizedStratum::code))
                    .toList();
            if (strata.stream().map(NormalizedStratum::code).distinct().count()
                    != strata.size()) {
                throw invalid();
            }
        }
    }

    record NormalizedStratum(
            String code,
            long populationCount,
            List<NormalizedSubjectWindow> selectedWindows) {
        public NormalizedStratum {
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}")
                    || populationCount < 0
                    || populationCount > 9_007_199_254_740_991L
                    || selectedWindows == null
                    || selectedWindows.size() > 10_000
                    || selectedWindows.size() > populationCount) {
                throw invalid();
            }
            selectedWindows = selectedWindows.stream()
                    .sorted(Comparator.comparing(NormalizedSubjectWindow::selectionRankDigest))
                    .toList();
            if (selectedWindows.stream()
                    .map(NormalizedSubjectWindow::selectionRankDigest)
                    .distinct().count() != selectedWindows.size()) {
                throw invalid();
            }
        }
    }

    record NormalizedSubjectWindow(
            String selectionRankDigest,
            String expectedDigest,
            String normalizedInputDigest) {
        public NormalizedSubjectWindow {
            if (!digest(selectionRankDigest) || !digest(expectedDigest)
                    || !digest(normalizedInputDigest)) {
                throw invalid();
            }
        }
    }

    record Unavailable() implements RecoverySampleNormalizedInputResolution {}

    record UnknownBindings() implements RecoverySampleNormalizedInputResolution {}

    private static boolean digest(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("RECOVERY_SAMPLE_NORMALIZED_INPUT_INVALID");
    }
}
