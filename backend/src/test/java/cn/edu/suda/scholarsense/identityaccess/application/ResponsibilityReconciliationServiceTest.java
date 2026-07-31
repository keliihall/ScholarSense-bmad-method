package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResponsibilityReconciliationServiceTest {
    private static final Instant NOW =
            Instant.parse("2026-07-30T00:00:00Z");
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "responsibility-authority",
            "sandbox-0",
            "responsibility");
    private static final UUID JOB_ID =
            UUID.fromString("019c1234-0000-7000-8000-000000000801");

    @Test
    void exactNinetyNinePointNineHasDifferencesButDoesNotFailThreshold() {
        List<ResponsibilitySnapshotEntry> expected =
                entries(1000);
        List<ResponsibilitySnapshotEntry> actual =
                expected.subList(0, 999);

        ResponsibilityReconciliationResult result =
                ResponsibilityReconciliationService.compare(
                        attempt(),
                        snapshot(expected),
                        actual,
                        0,
                        NOW.plusSeconds(1));

        assertEquals(
                new BigDecimal("0.999000"),
                result.matchRate());
        assertEquals("succeeded", result.jobOutcome());
        assertEquals(
                "differences-found",
                result.reconciliationOutcome());
        assertEquals(1, result.missing());
        assertFalse(result.qualityPassed());
    }

    @Test
    void belowThresholdOrActiveUnmappedIsThresholdFailed() {
        List<ResponsibilitySnapshotEntry> expected =
                entries(1000);
        var below = ResponsibilityReconciliationService.compare(
                attempt(),
                snapshot(expected),
                expected.subList(0, 998),
                0,
                NOW.plusSeconds(1));
        assertEquals(new BigDecimal("0.998000"), below.matchRate());
        assertEquals(
                "threshold-failed",
                below.reconciliationOutcome());

        ResponsibilitySnapshotEntry source = expected.getFirst();
        ResponsibilitySnapshotEntry unmapped =
                new ResponsibilitySnapshotEntry(
                        source.relationRefToken(),
                        source.studentSourceRefDigest(),
                        source.recordVersion(),
                        source.payloadDigest(),
                        true,
                        false);
        var activeUnmapped =
                ResponsibilityReconciliationService.compare(
                        attempt(),
                        snapshot(List.of(source)),
                        List.of(unmapped),
                        1,
                        NOW.plusSeconds(1));
        assertEquals(1, activeUnmapped.activeUnmappedCount());
        assertEquals(
                "threshold-failed",
                activeUnmapped.reconciliationOutcome());
        assertEquals("succeeded", activeUnmapped.jobOutcome());
    }

    @Test
    void emptyCompleteSnapshotAndDuplicateSemanticsAreExplicit() {
        var empty = ResponsibilityReconciliationService.compare(
                attempt(),
                snapshot(List.of()),
                List.of(),
                0,
                NOW.plusSeconds(1));
        assertEquals(new BigDecimal("1.000000"), empty.matchRate());
        assertEquals("matched", empty.reconciliationOutcome());
        assertTrue(empty.qualityPassed());

        ResponsibilitySnapshotEntry entry = entries(1).getFirst();
        var duplicate = ResponsibilityReconciliationService.compare(
                attempt(),
                snapshot(List.of(entry, entry)),
                List.of(entry),
                0,
                NOW.plusSeconds(1));
        assertEquals(1, duplicate.versionDrift());
        assertEquals(
                "threshold-failed",
                duplicate.reconciliationOutcome());
        assertEquals("duplicate",
                duplicate.differences().getFirst().differenceType());
    }

    @Test
    void studentEquivalenceDomainParticipatesInDigestAndMatch() {
        ResponsibilitySnapshotEntry expected =
                entries(1).getFirst();
        ResponsibilitySnapshotEntry drifted =
                new ResponsibilitySnapshotEntry(
                        expected.relationRefToken(),
                        hex(999),
                        expected.recordVersion(),
                        expected.payloadDigest(),
                        false,
                        false);

        ResponsibilityReconciliationResult result =
                ResponsibilityReconciliationService.compare(
                        attempt(),
                        snapshot(List.of(expected)),
                        List.of(drifted),
                        0,
                        NOW.plusSeconds(1));

        assertEquals(1, result.versionDrift());
        assertEquals(0, result.matched());
        assertFalse(
                ResponsibilityReconciliationService.digest(
                                List.of(expected))
                        .equals(
                                ResponsibilityReconciliationService.digest(
                                        List.of(drifted))));
    }

    private static List<ResponsibilitySnapshotEntry> entries(
            int count) {
        List<ResponsibilitySnapshotEntry> values =
                new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String suffix = String.format("%040d", index);
            values.add(new ResponsibilitySnapshotEntry(
                    "rtok_" + suffix,
                    hex(index + 1),
                    7,
                    hex(index + 2),
                    false,
                    false));
        }
        return List.copyOf(values);
    }

    private static String hex(int value) {
        return String.format("%064x", value);
    }

    private static ResponsibilityFullSnapshot snapshot(
            List<ResponsibilitySnapshotEntry> entries) {
        return new ResponsibilityFullSnapshot(
                UUID.fromString(
                        "019c1234-0000-7000-8000-000000000802"),
                KEY,
                "RESPONSIBILITY-SNAPSHOT-1.0.0",
                "RESPONSIBILITY-AUTHORITY-1.0.0",
                LocalDate.of(2026, 7, 30),
                NOW,
                7,
                7,
                Map.of("identity-authority|sandbox-0", 42L),
                true,
                true,
                List.of("sandbox-0"),
                entries.size(),
                ResponsibilityReconciliationService.digest(entries),
                "a".repeat(64),
                true,
                entries,
                "0123456789abcdef0123456789abcdef");
    }

    private static RunningResponsibilityReconciliationAttempt
            attempt() {
        return new RunningResponsibilityReconciliationAttempt(
                JOB_ID,
                KEY,
                LocalDate.of(2026, 7, 30),
                1,
                8,
                "0123456789abcdef0123456789abcdef",
                NOW,
                new ResponsibilityReconciliationLease(
                        KEY,
                        LocalDate.of(2026, 7, 30),
                        JOB_ID,
                        1,
                        9,
                        "reconciliation-worker",
                        NOW,
                        NOW.plus(Duration.ofMinutes(5))));
    }
}
