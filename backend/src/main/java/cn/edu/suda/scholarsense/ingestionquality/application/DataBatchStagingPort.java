package cn.edu.suda.scholarsense.ingestionquality.application;

/** Receiving-stage evidence writes, deliberately separate from lifecycle owner commits. */
public interface DataBatchStagingPort {
    boolean appendNormalizedFact(AppendNormalizedFactCommand command);

    boolean recordQualityMeasurement(RecordQualityMeasurementCommand command);

    boolean recordQualityImpactScope(RecordQualityImpactScopeCommand command);
}
