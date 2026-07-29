package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Map;

public record IdentitySyncObservation(
        String metric,
        long value,
        Map<String, String> labels,
        String traceId,
        Instant observedAt) {
    public IdentitySyncObservation {
        labels = Map.copyOf(labels);
        if (labels.keySet().stream().anyMatch(key ->
                key.toLowerCase(java.util.Locale.ROOT).contains("external")
                        || key.toLowerCase(java.util.Locale.ROOT).contains("account"))) {
            throw new IllegalArgumentException("IDENTITY_METRIC_HIGH_CARDINALITY_LABEL");
        }
    }
}
