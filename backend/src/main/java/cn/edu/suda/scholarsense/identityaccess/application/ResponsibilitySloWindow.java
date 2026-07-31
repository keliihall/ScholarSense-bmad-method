package cn.edu.suda.scholarsense.identityaccess.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rolling 30-day SLO numerator and denominator, including pending compensation. */
public record ResponsibilitySloWindow(long numerator, long denominator) {
    public ResponsibilitySloWindow {
        if (numerator < 0
                || denominator < 0
                || numerator > denominator) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SLO_WINDOW_INVALID");
        }
    }

    public BigDecimal rate() {
        return denominator == 0
                ? BigDecimal.ZERO.setScale(6)
                : BigDecimal.valueOf(numerator)
                        .divide(
                                BigDecimal.valueOf(denominator),
                                6,
                                RoundingMode.HALF_UP);
    }

    public boolean meetsTarget() {
        return denominator > 0
                && rate().compareTo(new BigDecimal("0.990000")) >= 0;
    }
}
