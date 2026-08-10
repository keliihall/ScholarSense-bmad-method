package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Typed transport for a production consumer-registry authority assertion, never an owner result. */
public record QualitySnapshotRetentionAuthorityEvidence(
        UUID authorityEvidenceId, byte[] payloadUtf8, String payloadDigest) {
    private static final int MAX_PAYLOAD_BYTES = 65_536;

    public QualitySnapshotRetentionAuthorityEvidence {
        Objects.requireNonNull(authorityEvidenceId);
        if (authorityEvidenceId.version() != 7 || authorityEvidenceId.variant() != 2) {
            throw invalid();
        }
        Objects.requireNonNull(payloadUtf8);
        if (payloadUtf8.length < 2 || payloadUtf8.length > MAX_PAYLOAD_BYTES) throw invalid();
        payloadUtf8 = Arrays.copyOf(payloadUtf8, payloadUtf8.length);
        if (payloadDigest == null || !payloadDigest.matches("[0-9a-f]{64}")) throw invalid();
    }

    @Override
    public byte[] payloadUtf8() {
        return Arrays.copyOf(payloadUtf8, payloadUtf8.length);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "INGESTION_QUALITY_RETENTION_AUTHORITY_EVIDENCE_INVALID");
    }
}
