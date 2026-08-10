package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.MeasuredQualityInputs;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Formula-scoped measurements plus the exact sealed evidence they were read from. */
public record QualityMeasurement(
        QualityMeasurementAnchor anchor,
        Map<String, MeasuredQualityInputs> inputsByFormula,
        List<String> impactScopeCodes) {
    public QualityMeasurement {
        Objects.requireNonNull(anchor);
        inputsByFormula = Map.copyOf(inputsByFormula);
        impactScopeCodes = List.copyOf(impactScopeCodes);
    }
}
