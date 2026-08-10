package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatch;
import cn.edu.suda.scholarsense.ingestionquality.domain.ExecutableQualityPolicy.MetricDefinition;
import java.util.List;

/**
 * Supplies formula-scoped integer measurements for a sealed batch.
 *
 * <p>The calculator owns applicability, policy operands and results. An adapter may only measure
 * the approved operand identifiers; it cannot submit thresholds, decisions, or a synthetic raw
 * count.
 */
@FunctionalInterface
public interface QualityMeasurementPort {
    QualityMeasurement measure(DataBatch sealedBatch, List<MetricDefinition> orderedDefinitions);
}
