package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Objects;

/** Exact Story 2.3 formula outcome consumed by the fuse without recalculation. */
public record QualityFuseFormulaBoundaryEvidence(
        String metricId,
        String formulaId,
        String formulaVersion,
        String result,
        boolean applicable,
        long numerator,
        long denominator,
        Long valueBasisPoints,
        String unit,
        String operator,
        long thresholdNumerator,
        long thresholdDenominator,
        String boundary,
        Boolean comparisonResult) {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    public QualityFuseFormulaBoundaryEvidence {
        metricId = text(metricId, 128);
        formulaId = text(formulaId, 256);
        formulaVersion = text(formulaVersion, 64);
        if (!formulaId.startsWith("QMDP-1.0.0/") || !"1.0.0".equals(formulaVersion)
                || !Objects.equals(comparisonResult,
                        "not-applicable".equals(result) ? null : "passed".equals(result))
                || !("passed".equals(result) || "failed".equals(result)
                        || "not-applicable".equals(result))
                || !("basis-point".equals(unit) || "count".equals(unit)
                        || "millisecond".equals(unit) || "member-count".equals(unit))
                || !(">=".equals(operator) || "<=".equals(operator) || "=".equals(operator))
                || !("inclusive".equals(boundary) || "exclusive".equals(boundary))) {
            throw invalid();
        }
        safe(numerator);
        safe(denominator);
        safe(thresholdNumerator);
        safe(thresholdDenominator);
        if (valueBasisPoints != null) safe(valueBasisPoints);
        if (thresholdDenominator < 1
                || (applicable && denominator < 1)
                || (!applicable && (denominator != 0 || numerator != 0
                        || valueBasisPoints != null || comparisonResult != null
                        || !"not-applicable".equals(result)))) {
            throw invalid();
        }
    }

    private static String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) throw invalid();
        return value;
    }

    private static void safe(long value) {
        if (value < 0 || value > MAX_SAFE_INTEGER) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_FUSE_FORMULA_EVIDENCE_INVALID");
    }
}
