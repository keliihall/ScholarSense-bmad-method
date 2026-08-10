package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Closed measured operands for one approved metric formula. */
public record MeasuredQualityInputs(boolean applicable, Map<String, BigInteger> operands) {
    private static final BigInteger MAX_SAFE_INTEGER =
            BigInteger.valueOf(IngestionQualityDomainRules.MAX_SAFE_VERSION);

    public MeasuredQualityInputs {
        Objects.requireNonNull(operands);
        LinkedHashMap<String, BigInteger> frozen = new LinkedHashMap<>();
        operands.forEach((operandId, value) -> {
            String id = IngestionQualityDomainRules.requireText(operandId, 128);
            BigInteger safe = Objects.requireNonNull(value);
            if (safe.signum() < 0 || safe.compareTo(MAX_SAFE_INTEGER) > 0
                    || frozen.put(id, safe) != null) {
                throw IngestionQualityDomainRules.invalid();
            }
        });
        operands = Collections.unmodifiableMap(frozen);
    }
}
