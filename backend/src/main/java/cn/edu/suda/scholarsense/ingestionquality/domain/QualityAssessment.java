package cn.edu.suda.scholarsense.ingestionquality.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A complete business assessment. Technical calculation errors never create this value. */
public record QualityAssessment(
        QualityOverallResult overallResult,
        List<QualityMetricResult> metricResults) {

    public QualityAssessment {
        Objects.requireNonNull(overallResult);
        metricResults = List.copyOf(metricResults);
        if (metricResults.isEmpty()) throw IngestionQualityDomainRules.invalid();

        Set<String> formulas = new HashSet<>();
        int applicable = 0;
        boolean failed = false;
        for (QualityMetricResult metric : metricResults) {
            Objects.requireNonNull(metric);
            if (!formulas.add(metric.formulaId())) throw IngestionQualityDomainRules.invalid();
            if (metric.applicable()) {
                applicable++;
                failed |= metric.result() == QualityMetricResultStatus.FAILED;
            }
        }
        if (applicable == 0
                || failed != (overallResult == QualityOverallResult.QUALITY_FAILED)) {
            throw IngestionQualityDomainRules.invalid();
        }
    }
}
