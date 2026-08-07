package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.LinkedHashMap;
import java.util.Map;

public final class SubjectMappingConsumerRegistry {
    private final Map<String, ConsumerState> consumers = new LinkedHashMap<>();

    public void registerActive(String consumerId, long watermark, boolean reconciliationPassed) {
        requireId(consumerId);
        if (watermark < 0) {
            throw new IllegalArgumentException("watermark");
        }
        consumers.put(consumerId, new ConsumerState(true, watermark, reconciliationPassed, "none"));
    }

    public void registerPlanned(String consumerId, String runtimeEvidenceClaim) {
        requireId(consumerId);
        if (!"runtimeEvidenceClaim=none".equals(runtimeEvidenceClaim)) {
            throw new IllegalArgumentException("planned runtime evidence");
        }
        consumers.put(consumerId, new ConsumerState(false, 0, false, "none"));
    }

    public boolean correctionCompleteAt(long aggregateVersion) {
        boolean hasActive = false;
        for (ConsumerState state : consumers.values()) {
            if (!state.active()) {
                continue;
            }
            hasActive = true;
            if (!state.reconciliationPassed() || state.watermark() < aggregateVersion) {
                return false;
            }
        }
        return hasActive;
    }

    private static void requireId(String consumerId) {
        if (consumerId == null || !consumerId.matches("[a-z][a-z0-9-]+")) {
            throw new IllegalArgumentException("consumerId");
        }
    }

    private record ConsumerState(
            boolean active, long watermark, boolean reconciliationPassed, String runtimeEvidenceClaim) {}
}
