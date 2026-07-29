package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdentityReconciliationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-24T00:02:00Z");
    private static final String TRACE = "0123456789abcdef0123456789abcdef";

    @Test
    void reportsMatchedMissingUnexpectedAndVersionDriftWithoutMutation() {
        var saved = new ArrayList<IdentityReconciliationResult>();
        var audits = new ArrayList<IdentitySyncAuditEvent>();
        var service = new IdentityReconciliationService(
                saved::add, audits::add, IdentityReconciliationServiceTest::trustedNow);
        var expected = snapshot(List.of(
                entry("a", 7, "a-structure"),
                entry("b", 7, "b-structure"),
                entry("c", 7, "c-structure")));
        var actual = snapshot(List.of(
                entry("a", 7, "a-structure"),
                entry("b", 8, "b-changed"),
                entry("d", 7, "d-structure")));

        IdentityReconciliationResult result =
                service.compare(expected, actual, TRACE);

        assertEquals(1, result.matched());
        assertEquals(1, result.missing());
        assertEquals(1, result.unexpected());
        assertEquals(1, result.versionDrift());
        assertFalse(result.mutationApplied());
        assertFalse(result.matchedCompletely());
        assertEquals(result, saved.getFirst());
        assertEquals("IDENTITY_RECONCILIATION_DIFFERENCES_FOUND",
                audits.getFirst().reasonCode());
    }

    @Test
    void equalSnapshotsProduceStableMatchedEvidence() {
        var service = new IdentityReconciliationService(
                ignored -> {}, ignored -> {}, IdentityReconciliationServiceTest::trustedNow);
        var snapshot = snapshot(List.of(entry("a", 7, "a-structure")));

        IdentityReconciliationResult result =
                service.compare(snapshot, snapshot, TRACE);

        assertTrue(result.matchedCompletely());
        assertEquals(result.expectedDigest(), result.actualDigest());
        assertEquals(1, result.matched());
    }

    @Test
    void evidenceAndAuditRollbackTogetherWhenAuditAppendFails() {
        var saved = new ArrayList<IdentityReconciliationResult>();
        var service = new IdentityReconciliationService(
                saved::add,
                ignored -> {
                    throw new IllegalStateException("audit unavailable");
                },
                IdentityReconciliationServiceTest::trustedNow,
                new IdentitySyncTransactionPort() {
                    @Override
                    public <T> T execute(java.util.function.Supplier<T> work) {
                        int before = saved.size();
                        try {
                            return work.get();
                        } catch (RuntimeException failure) {
                            while (saved.size() > before) {
                                saved.removeLast();
                            }
                            throw failure;
                        }
                    }
                });
        var snapshot = snapshot(List.of(entry("a", 7, "a-structure")));

        assertThrows(
                IllegalStateException.class,
                () -> service.compare(snapshot, snapshot, TRACE));

        assertTrue(saved.isEmpty());
    }

    private static IdentityReconciliationSnapshot snapshot(
            List<IdentityReconciliationEntry> entries) {
        return new IdentityReconciliationSnapshot(
                "identity-org-sandbox-sample", 7, 7, entries);
    }

    private static IdentityReconciliationEntry entry(
            String key, long version, String structure) {
        return new IdentityReconciliationEntry(
                digest(key), IdentityRecordKind.ACCOUNT, version, digest(structure));
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static TrustedTime trustedNow() {
        return new TrustedTime(
                NOW,
                new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(50),
                        "evidence://signed/clock/campus-ntp-a.json"));
    }
}
