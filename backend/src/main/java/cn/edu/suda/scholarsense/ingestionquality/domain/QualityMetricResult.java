package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.math.BigInteger;
import java.util.Objects;

/** Immutable QSHM metric evidence; rawCount is intentionally not an independent field. */
public record QualityMetricResult(
        String metricId,
        String formulaId,
        String formulaVersion,
        QualityMetricResultStatus result,
        boolean applicable,
        BigInteger numerator,
        BigInteger denominator,
        BigInteger valueBasisPoints,
        QualityMetricUnit unit,
        QualityMetricOperator operator,
        BigInteger thresholdNumerator,
        BigInteger thresholdDenominator,
        QualityMetricBoundary boundary,
        String reasonCode) {
    private static final BigInteger MAX_SAFE_INTEGER =
            BigInteger.valueOf(IngestionQualityDomainRules.MAX_SAFE_VERSION);
    private static final BigInteger BASIS_POINT_SCALE = BigInteger.valueOf(10_000);

    public QualityMetricResult {
        metricId = IngestionQualityDomainRules.requireText(metricId, 128);
        formulaId = IngestionQualityDomainRules.requireText(formulaId, 256);
        if (!formulaId.startsWith("QMDP-1.0.0/") || !"1.0.0".equals(formulaVersion)) {
            throw IngestionQualityDomainRules.invalid();
        }
        Objects.requireNonNull(result);
        numerator = safeInteger(numerator);
        denominator = safeInteger(denominator);
        if (valueBasisPoints != null) valueBasisPoints = safeInteger(valueBasisPoints);
        Objects.requireNonNull(unit);
        Objects.requireNonNull(operator);
        thresholdNumerator = safeInteger(thresholdNumerator);
        thresholdDenominator = safeInteger(thresholdDenominator);
        Objects.requireNonNull(boundary);
        if (thresholdDenominator.signum() == 0 || reasonCode != null) {
            throw IngestionQualityDomainRules.invalid();
        }

        if (!applicable) {
            if (result != QualityMetricResultStatus.NOT_APPLICABLE
                    || numerator.signum() != 0
                    || denominator.signum() != 0
                    || valueBasisPoints != null) {
                throw IngestionQualityDomainRules.invalid();
            }
        } else {
            if (result == QualityMetricResultStatus.NOT_APPLICABLE
                    || denominator.signum() == 0) {
                throw IngestionQualityDomainRules.invalid();
            }
            boolean scaled = unit == QualityMetricUnit.BASIS_POINT
                    || unit == QualityMetricUnit.MEMBER_COUNT;
            boolean calendarExactRatio = formulaId.equals(
                    "QMDP-1.0.0/SRC-P0-CALENDAR-001/"
                            + "calendar-exactly-one-current-day-type");
            boolean unitDenominatorInvalid =
                    (unit == QualityMetricUnit.COUNT || unit == QualityMetricUnit.MILLISECOND)
                            && !BigInteger.ONE.equals(denominator);
            boolean memberCardinalityInvalid = unit == QualityMetricUnit.MEMBER_COUNT
                    && numerator.compareTo(denominator) > 0;
            boolean ratioCardinalityInvalid = unit == QualityMetricUnit.BASIS_POINT
                    && !calendarExactRatio
                    && numerator.compareTo(denominator) > 0;
            if (unitDenominatorInvalid || memberCardinalityInvalid || ratioCardinalityInvalid) {
                throw IngestionQualityDomainRules.invalid();
            }
            if (scaled != (valueBasisPoints != null)) {
                throw IngestionQualityDomainRules.invalid();
            }
            if (scaled && !roundedBasisPoints(numerator, denominator)
                    .equals(valueBasisPoints)) {
                throw IngestionQualityDomainRules.invalid();
            }
            boolean passed = compare(
                    numerator, denominator, thresholdNumerator, thresholdDenominator, operator);
            if (passed != (result == QualityMetricResultStatus.PASSED)) {
                throw IngestionQualityDomainRules.invalid();
            }
        }
    }

    private static BigInteger safeInteger(BigInteger value) {
        BigInteger integer = Objects.requireNonNull(value);
        if (integer.signum() < 0 || integer.compareTo(MAX_SAFE_INTEGER) > 0) {
            throw IngestionQualityDomainRules.invalid();
        }
        return integer;
    }

    static BigInteger roundedBasisPoints(BigInteger numerator, BigInteger denominator) {
        BigInteger[] quotientAndRemainder =
                numerator.multiply(BASIS_POINT_SCALE).divideAndRemainder(denominator);
        if (quotientAndRemainder[1].shiftLeft(1).compareTo(denominator) >= 0) {
            return quotientAndRemainder[0].add(BigInteger.ONE);
        }
        return quotientAndRemainder[0];
    }

    static boolean compare(
            BigInteger numerator,
            BigInteger denominator,
            BigInteger thresholdNumerator,
            BigInteger thresholdDenominator,
            QualityMetricOperator operator) {
        int comparison = numerator.multiply(thresholdDenominator)
                .compareTo(denominator.multiply(thresholdNumerator));
        return switch (operator) {
            case GREATER_THAN_OR_EQUAL -> comparison >= 0;
            case LESS_THAN_OR_EQUAL -> comparison <= 0;
            case EQUAL -> comparison == 0;
        };
    }
}
