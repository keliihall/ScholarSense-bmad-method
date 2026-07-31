package cn.edu.suda.scholarsense.identityaccess.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ResponsibilityReconciliationResult(
        UUID runId,
        UUID jobId,
        CheckpointKey key,
        LocalDate businessDate,
        long sourceVersion,
        long throughWatermark,
        Map<String, Long> supportingIdentityOrgWatermarks,
        long expectedCount,
        long actualCount,
        String expectedDigest,
        String actualDigest,
        long matched,
        long missing,
        long unexpected,
        long versionDrift,
        BigDecimal matchRate,
        long activeUnmappedCount,
        long exceptionCount,
        String jobOutcome,
        String reconciliationOutcome,
        String reasonCode,
        long fencingToken,
        Instant startedAt,
        Instant completedAt,
        String traceId,
        List<ResponsibilityReconciliationDifference> differences) {
    public ResponsibilityReconciliationResult {
        if (runId == null
                || runId.version() != 7
                || runId.variant() != 2
                || jobId == null
                || key == null
                || businessDate == null
                || sourceVersion < 1
                || throughWatermark < 0
                || expectedCount < 0
                || actualCount < 0
                || matched < 0
                || missing < 0
                || unexpected < 0
                || versionDrift < 0
                || matchRate == null
                || matchRate.compareTo(BigDecimal.ZERO) < 0
                || matchRate.compareTo(BigDecimal.ONE) > 0
                || matchRate.scale() != 6
                || activeUnmappedCount < 0
                || exceptionCount < 0
                || !"succeeded".equals(jobOutcome)
                || !java.util.Set.of(
                                "matched",
                                "differences-found",
                                "threshold-failed")
                        .contains(reconciliationOutcome)
                || reasonCode == null
                || !reasonCode.matches(
                        "RESPONSIBILITY_[A-Z0-9_]+")
                || fencingToken < 1
                || startedAt == null
                || completedAt == null
                || completedAt.isBefore(startedAt)
                || traceId == null
                || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_RECONCILIATION_RESULT_INVALID");
        }
        supportingIdentityOrgWatermarks =
                Map.copyOf(supportingIdentityOrgWatermarks);
        differences = List.copyOf(differences);
    }

    public boolean qualityPassed() {
        return "matched".equals(reconciliationOutcome);
    }
}
