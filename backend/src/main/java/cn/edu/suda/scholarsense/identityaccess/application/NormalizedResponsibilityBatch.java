package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record NormalizedResponsibilityBatch(
        UUID batchId,
        CheckpointKey key,
        String schemaVersion,
        String contractVersion,
        long sourceVersion,
        long fromWatermark,
        long toWatermark,
        Map<String, Long> supportingIdentityOrgWatermarks,
        Instant sourceVisibleAt,
        Instant observedAt,
        String traceId,
        String envelopeDigest,
        String signatureDigest,
        boolean signatureVerified,
        byte[] encryptedEnvelope,
        byte[] wrappedDataKey,
        byte[] encryptionNonce,
        String encryptionKeyRef,
        String encryptionKeyVersion,
        List<AuthoritativeResponsibilityRelation> relations) {
    public NormalizedResponsibilityBatch {
        if (batchId == null || batchId.version() != 7 || batchId.variant() != 2) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_BATCH_UUIDV7_REQUIRED");
        }
        Objects.requireNonNull(key, "key");
        if (!"responsibility".equals(key.consumerProjection())
                || !"RESPONSIBILITY-BATCH-1.0.0".equals(schemaVersion)
                || !"RESPONSIBILITY-AUTHORITY-1.0.0".equals(contractVersion)
                || sourceVersion < 1
                || fromWatermark < 0
                || toWatermark < fromWatermark) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_BATCH_ENVELOPE_INVALID");
        }
        supportingIdentityOrgWatermarks =
                Map.copyOf(supportingIdentityOrgWatermarks);
        if (supportingIdentityOrgWatermarks.isEmpty()
                || supportingIdentityOrgWatermarks.entrySet().stream().anyMatch(
                        entry -> !entry.getKey().matches(
                                        "[a-z][a-z0-9-]{2,63}\\|[a-z0-9][a-z0-9-]{0,63}")
                                || entry.getValue() == null
                                || entry.getValue() < 0)) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_DEPENDENCY_VECTOR_INVALID");
        }
        Objects.requireNonNull(sourceVisibleAt, "sourceVisibleAt");
        Objects.requireNonNull(observedAt, "observedAt");
        if (observedAt.isBefore(sourceVisibleAt)
                || traceId == null
                || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_BATCH_ENVELOPE_INVALID");
        }
        requireDigest(envelopeDigest);
        requireDigest(signatureDigest);
        encryptedEnvelope = Arrays.copyOf(encryptedEnvelope, encryptedEnvelope.length);
        wrappedDataKey = Arrays.copyOf(wrappedDataKey, wrappedDataKey.length);
        encryptionNonce = Arrays.copyOf(encryptionNonce, encryptionNonce.length);
        if (encryptedEnvelope.length == 0
                || wrappedDataKey.length == 0
                || encryptionNonce.length == 0
                || encryptionKeyRef == null
                || !encryptionKeyRef.matches(
                        "config://(dev|test|stage|prod)/[a-z0-9-]+")
                || encryptionKeyVersion == null
                || !encryptionKeyVersion.matches("k[0-9]+")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_BATCH_ENCRYPTION_INVALID");
        }
        relations = List.copyOf(relations);
        boolean noChange = toWatermark == fromWatermark;
        if ((noChange && !relations.isEmpty())
                || (!noChange && relations.isEmpty())
                || relations.stream().anyMatch(relation ->
                        relation.sourceVersion() != sourceVersion
                                || relation.sourceWatermark() != toWatermark)) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_BATCH_RECORDS_INCOMPLETE");
        }
    }

    public boolean noChange() {
        return fromWatermark == toWatermark;
    }

    @Override
    public byte[] encryptedEnvelope() {
        return Arrays.copyOf(encryptedEnvelope, encryptedEnvelope.length);
    }

    @Override
    public byte[] wrappedDataKey() {
        return Arrays.copyOf(wrappedDataKey, wrappedDataKey.length);
    }

    @Override
    public byte[] encryptionNonce() {
        return Arrays.copyOf(encryptionNonce, encryptionNonce.length);
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_BATCH_DIGEST_INVALID");
        }
    }
}
