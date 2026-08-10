package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualityMetricResult;
import java.math.BigInteger;

public record QualitySnapshotMetricView(
        String metricId,
        String formulaId,
        String formulaVersion,
        String result,
        boolean applicable,
        BigInteger numerator,
        BigInteger denominator,
        BigInteger valueBasisPoints,
        String unit,
        String operator,
        BigInteger thresholdNumerator,
        BigInteger thresholdDenominator,
        String boundary,
        String reasonCode) {
    static QualitySnapshotMetricView from(QualityMetricResult metric) {
        return new QualitySnapshotMetricView(
                metric.metricId(), metric.formulaId(), metric.formulaVersion(),
                metric.result().wireValue(), metric.applicable(), metric.numerator(),
                metric.denominator(), metric.valueBasisPoints(), metric.unit().wireValue(),
                metric.operator().wireValue(), metric.thresholdNumerator(),
                metric.thresholdDenominator(), metric.boundary().wireValue(), metric.reasonCode());
    }
}
