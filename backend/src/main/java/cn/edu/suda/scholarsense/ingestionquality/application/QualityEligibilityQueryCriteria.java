package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record QualityEligibilityQueryCriteria(
        String status,
        String ruleId,
        Instant afterOccurredAt,
        UUID afterEligibilityId,
        int limit) {
    private static final Set<String> STATUSES = Set.of(
            "eligible", "fused", "recovering", "missing");

    public QualityEligibilityQueryCriteria {
        if (status != null && !STATUSES.contains(status)
                || ruleId != null && !ruleId.matches("^[A-Z][A-Z0-9-]{2,63}$")
                || (afterOccurredAt == null) != (afterEligibilityId == null)
                || afterEligibilityId != null
                        && (afterEligibilityId.version() != 7 || afterEligibilityId.variant() != 2)
                || limit < 1 || limit > 101) {
            throw new IllegalArgumentException("INGESTION_QUALITY_ELIGIBILITY_QUERY_INVALID");
        }
    }
}
