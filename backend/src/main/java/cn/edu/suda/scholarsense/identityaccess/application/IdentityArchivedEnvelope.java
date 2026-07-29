package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One durable, encrypted batch archive retained for projection rebuild. */
public record IdentityArchivedEnvelope(
        UUID batchId,
        CheckpointKey key,
        String schemaVersion,
        long sourceVersion,
        long fromWatermark,
        long toWatermark,
        Instant sourceVisibleAt,
        Instant observedAt,
        Instant appliedAt,
        String traceId,
        String mappingVersion,
        String mappingDigest,
        String envelopeDigest,
        String signatureDigest,
        EncryptedSecret encrypted) {

    public IdentityArchivedEnvelope {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(sourceVisibleAt, "sourceVisibleAt");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(appliedAt, "appliedAt");
        Objects.requireNonNull(encrypted, "encrypted");
        if (!"IDENTITY-AUTHORITY-BATCH-1.0.0".equals(schemaVersion)
                || sourceVersion < 1
                || fromWatermark < 0
                || toWatermark <= fromWatermark
                || !traceId.matches("[0-9a-f]{32}")
                || !"IDENTITY-ROLE-MAPPING-1.0.0".equals(mappingVersion)
                || !mappingDigest.matches("[0-9a-f]{64}")
                || !envelopeDigest.matches("[0-9a-f]{64}")
                || !signatureDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("IDENTITY_ARCHIVE_INVALID");
        }
    }
}
