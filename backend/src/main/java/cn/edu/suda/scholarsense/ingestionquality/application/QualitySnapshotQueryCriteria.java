package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.UUID;

/** Stable keyset query over assessed snapshots only. */
public record QualitySnapshotQueryCriteria(
        String sourceId,
        String overallResult,
        Instant evaluatedFrom,
        Instant evaluatedTo,
        String sortField,
        String sortDirection,
        Instant afterEvaluatedAt,
        UUID afterSnapshotId,
        int limit) {
    public QualitySnapshotQueryCriteria {
        if (sourceId != null && !sourceId.matches("^SRC-P[01]-[A-Z-]+-[0-9]{3}$")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_QUERY_SOURCE_INVALID");
        }
        if (overallResult != null
                && !java.util.Set.of("quality-passed", "quality-failed").contains(overallResult)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_QUERY_RESULT_INVALID");
        }
        if (evaluatedFrom != null && evaluatedTo != null
                && !evaluatedFrom.isBefore(evaluatedTo)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_QUERY_WINDOW_INVALID");
        }
        if (!"evaluatedAt".equals(sortField)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_QUERY_SORT_FIELD_INVALID");
        }
        if (!java.util.Set.of("asc", "desc").contains(sortDirection)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_QUERY_SORT_DIRECTION_INVALID");
        }
        if ((afterEvaluatedAt == null) != (afterSnapshotId == null)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_QUERY_CURSOR_INVALID");
        }
        if (afterSnapshotId != null
                && (afterSnapshotId.version() != 7 || afterSnapshotId.variant() != 2)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_QUERY_CURSOR_INVALID");
        }
        if (limit < 1 || limit > 101) {
            throw new IllegalArgumentException("INGESTION_QUALITY_QUERY_LIMIT_INVALID");
        }
    }

    public QualitySnapshotQueryCriteria(
            String sourceId,
            String overallResult,
            Instant evaluatedFrom,
            Instant evaluatedTo,
            Instant afterEvaluatedAt,
            UUID afterSnapshotId,
            int limit) {
        this(sourceId, overallResult, evaluatedFrom, evaluatedTo, "evaluatedAt", "desc",
                afterEvaluatedAt, afterSnapshotId, limit);
    }
}
