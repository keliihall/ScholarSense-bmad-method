package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.UUID;

public record QualityRecoveryTaskQueryCriteria(
        String sourceId,
        String status,
        Instant afterOccurredAt,
        UUID afterTaskId,
        int limit) {
    public QualityRecoveryTaskQueryCriteria {
        if (sourceId != null && !sourceId.matches("^SRC-P[01]-[A-Z0-9-]+-[0-9]{3}$")) {
            throw invalid();
        }
        if (status != null && !java.util.List.of("open", "closed").contains(status)) {
            throw invalid();
        }
        if ((afterOccurredAt == null) != (afterTaskId == null) || limit < 1 || limit > 101) {
            throw invalid();
        }
        if (afterTaskId != null
                && (afterTaskId.version() != 7 || afterTaskId.variant() != 2)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_RECOVERY_TASK_QUERY_INVALID");
    }
}
