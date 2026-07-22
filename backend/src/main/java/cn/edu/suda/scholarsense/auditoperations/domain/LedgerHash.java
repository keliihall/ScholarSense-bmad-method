package cn.edu.suda.scholarsense.auditoperations.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** A lowercase hexadecimal SHA-256 value. */
public record LedgerHash(String value) {
    private static final String GENESIS = "0".repeat(64);

    public LedgerHash {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("AUDIT_LEDGER_HASH_INVALID");
        }
    }

    public static LedgerHash genesis() {
        return new LedgerHash(GENESIS);
    }

    public boolean constantTimeEquals(LedgerHash other) {
        return other != null && MessageDigest.isEqual(
                value.getBytes(StandardCharsets.US_ASCII),
                other.value.getBytes(StandardCharsets.US_ASCII));
    }

    public byte[] asciiBytes() {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
