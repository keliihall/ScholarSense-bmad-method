package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import java.util.Objects;
import java.util.UUID;

public record IdentitySourceFact(
        UUID eventId,
        IdentityRecordKind recordKind,
        String externalRefDigest,
        long sourceVersion,
        EffectiveInterval effectiveInterval,
        String payloadDigest,
        long aggregateVersion) {
    public IdentitySourceFact {
        if (eventId == null || eventId.version() != 7 || eventId.variant() != 2) {
            throw new IllegalArgumentException("IDENTITY_SOURCE_EVENT_UUIDV7_REQUIRED");
        }
        Objects.requireNonNull(recordKind, "recordKind");
        requireDigest(externalRefDigest, "IDENTITY_SOURCE_EXTERNAL_REF_INVALID");
        if (sourceVersion < 1 || aggregateVersion < 1) {
            throw new IllegalArgumentException("IDENTITY_SOURCE_FACT_VERSION_INVALID");
        }
        Objects.requireNonNull(effectiveInterval, "effectiveInterval");
        requireDigest(payloadDigest, "IDENTITY_SOURCE_PAYLOAD_DIGEST_INVALID");
    }

    private static void requireDigest(String value, String code) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(code);
        }
    }
}
