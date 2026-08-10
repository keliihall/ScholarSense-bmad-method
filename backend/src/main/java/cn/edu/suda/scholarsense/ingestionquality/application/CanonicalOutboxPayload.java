package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import java.util.Objects;
import java.util.Optional;

/** Canonical audit and optional business payloads built only inside the application boundary. */
public final class CanonicalOutboxPayload {
    private final LocalAuditOutboxRecord auditRecord;
    private final byte[] auditUtf8;
    private final String auditDigest;
    private final DataBatchBusinessOutboxEvent businessEvent;
    private final byte[] businessUtf8;
    private final String businessDigest;

    CanonicalOutboxPayload(
            LocalAuditOutboxRecord auditRecord,
            byte[] auditUtf8,
            String auditDigest,
            DataBatchBusinessOutboxEvent businessEvent,
            byte[] businessUtf8,
            String businessDigest) {
        this.auditRecord = Objects.requireNonNull(auditRecord);
        this.auditUtf8 = Objects.requireNonNull(auditUtf8).clone();
        this.auditDigest = hexDigest(auditDigest);
        this.businessEvent = businessEvent;
        this.businessUtf8 = businessUtf8 == null ? null : businessUtf8.clone();
        this.businessDigest = businessDigest == null ? null : hexDigest(businessDigest);
        if ((businessEvent == null) != (this.businessUtf8 == null)
                || (businessEvent == null) != (this.businessDigest == null)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_CANONICAL_PAYLOAD_INVALID");
        }
    }

    public LocalAuditOutboxRecord auditRecord() { return auditRecord; }

    public byte[] auditUtf8() { return auditUtf8.clone(); }

    public String auditDigest() { return auditDigest; }

    public Optional<DataBatchBusinessOutboxEvent> businessEvent() {
        return Optional.ofNullable(businessEvent);
    }

    public Optional<byte[]> businessUtf8() {
        return businessUtf8 == null ? Optional.empty() : Optional.of(businessUtf8.clone());
    }

    public Optional<String> businessDigest() {
        return Optional.ofNullable(businessDigest);
    }

    private static String hexDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_CANONICAL_PAYLOAD_INVALID");
        }
        return value;
    }
}
