package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.Map;

@FunctionalInterface
public interface AuditMetricSink {
    void record(String metricName, Map<String, String> labels);
}
