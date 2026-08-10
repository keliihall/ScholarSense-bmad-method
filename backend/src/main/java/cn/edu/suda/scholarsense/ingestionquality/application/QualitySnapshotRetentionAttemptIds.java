package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

/** Owner-generated identities for one retention attempt, including an unavailable authority call. */
public record QualitySnapshotRetentionAttemptIds(
        UUID resultEventId, UUID authorityEvidenceId) {
    public QualitySnapshotRetentionAttemptIds {
        resultEventId = uuidV7(resultEventId);
        authorityEvidenceId = uuidV7(authorityEvidenceId);
        if (new HashSet<>(java.util.List.of(resultEventId, authorityEvidenceId)).size() != 2) {
            throw invalid();
        }
    }

    private static UUID uuidV7(UUID value) {
        Objects.requireNonNull(value);
        if (value.version() != 7 || value.variant() != 2) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INGESTION_QUALITY_RETENTION_ATTEMPT_ID_INVALID");
    }
}
