package cn.edu.suda.scholarsense.auditoperations.application;

import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.NOW;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.fact;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.outbox;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHash;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalAuditHasherTest {

    private final CanonicalAuditHasher hasher = new CanonicalAuditHasher();

    @Test
    void canonicalJsonMatchesFrozenEmptyChineseNonBmpKeyOrderAndTimeVectors() throws Exception {
        LinkedHashMap<String, Object> emptyAndNull = new LinkedHashMap<>();
        emptyAndNull.put("empty", "");
        emptyAndNull.put("nil", null);
        assertVector(emptyAndNull,
                "f4deddc2cc212d43592b6a895059aeb6955c6731c6f2526c9c1769200669f784");
        assertVector(Map.of("message", "审计完整性"),
                "7bc7dbbca47700267bd63d1a940bfcfbd21f20c4a0e59355f4f14b471ca61d82");
        assertVector(Map.of("symbol", "𠮷"),
                "fc809d39fcc7fad33a2da67ed47f5fda4f5bbfcefc245c9dbc93963113c8a73e");
        LinkedHashMap<String, Object> reverseOrder = new LinkedHashMap<>();
        reverseOrder.put("z", 1);
        reverseOrder.put("a", 2);
        assertVector(reverseOrder,
                "c2985c5ba6f7d2a55e768f92490ca09388e95bc4cccb9fdf11b15f4d42f93e73");
        assertVector(Map.of("recordedAt", "2026-07-20T02:00:00.123Z"),
                "9b7a1d2fa183576fc5ff6f876ce0babbf3c86cef72a76555848aee7f7b04b754");
    }

    @Test
    void payloadFingerprintIsIndependentOfMapInsertionOrderAndBindsCompleteFact() {
        LinkedHashMap<String, String> first = new LinkedHashMap<>();
        first.put("zPolicy", "ZP-1.0.0");
        first.put("aPolicy", "AP-1.0.0");
        LinkedHashMap<String, String> second = new LinkedHashMap<>();
        second.put("aPolicy", "AP-1.0.0");
        second.put("zPolicy", "ZP-1.0.0");

        assertEquals(hasher.payloadFingerprint(fact(2L, first)), hasher.payloadFingerprint(fact(2L, second)));
        assertNotEquals(hasher.payloadFingerprint(fact(2L, first)), hasher.payloadFingerprint(fact(3L, first)));
    }

    @Test
    void entryHashBindsSequencePreviousHashSourceVersionsTimesTraceAndAggregateVersion() {
        LedgerHash payload = hasher.payloadFingerprint(fact());
        LedgerHash one = hasher.entryHash(1, LedgerHash.genesis(), outbox(), NOW.plusSeconds(1), payload);
        LedgerHash two = hasher.entryHash(2, LedgerHash.genesis(), outbox(), NOW.plusSeconds(1), payload);
        LedgerHash chained = hasher.entryHash(1, new LedgerHash("a".repeat(64)), outbox(), NOW.plusSeconds(1), payload);
        assertNotEquals(one, two);
        assertNotEquals(one, chained);
    }

    private void assertVector(Map<String, Object> value, String expected) throws Exception {
        byte[] canonical = CanonicalJson.canonicalBytes(value);
        String actual = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical));
        assertEquals(expected, actual, new String(canonical, StandardCharsets.UTF_8));
    }
}
