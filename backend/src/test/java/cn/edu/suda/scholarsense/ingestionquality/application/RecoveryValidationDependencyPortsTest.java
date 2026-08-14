package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RecoveryValidationDependencyPortsTest {

    private static final String A = "sha256:" + "a".repeat(64);
    private static final String B = "sha256:" + "b".repeat(64);
    private static final String C = "sha256:" + "c".repeat(64);
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final Instant AT = Instant.parse("2026-08-12T11:12:13.123456Z");

    @Test
    void backfillPortCarriesOnlyBoundEvidenceAndWatermarkDigests() {
        var request = new RecoveryBackfillRequest(
                id("1"), id("2"), id("3"), A, B, C, A, TRACE);
        RecoveryBackfillPort port = value -> RecoveryBackfillResult.available(
                value.startWatermarkDigest(), value.targetWatermarkDigest(), 120, B, AT,
                value.traceId());

        RecoveryBackfillResult result = port.execute(request);

        assertEquals(RecoveryValidationDependencyAvailability.AVAILABLE,
                result.availability());
        assertEquals(120, result.processedCount());
        assertEquals(B, result.summaryDigest());
    }

    @Test
    void fullReconciliationIsExactCountsAndDigestWithoutRawRowsOrFreeText() {
        var request = new RecoveryFullReconciliationRequest(
                id("1"), id("2"), id("3"), A, B, C, A, TRACE);
        RecoveryFullReconciliationPort port = value -> RecoveryFullReconciliationResult.available(
                120, 120, 0, B, AT, value.traceId());

        RecoveryFullReconciliationResult result = port.reconcile(request);

        assertEquals(RecoveryFullReconciliationResult.Coverage.FULL, result.coverage());
        assertEquals(0, result.mismatchCount());
        assertEquals(true, result.qualified());
        RecoveryFullReconciliationResult mismatch =
                RecoveryFullReconciliationResult.available(120, 119, 1, B, AT, TRACE);
        assertEquals(false, mismatch.qualified());
        assertThrows(IllegalArgumentException.class, () ->
                RecoveryFullReconciliationResult.available(120, 119, 121, B, AT, TRACE));
    }

    @Test
    void unavailableDependenciesAreNeverQualified() {
        RecoveryBackfillResult backfill = RecoveryBackfillResult.unavailable(
                RecoveryValidationDependencyError.DEPENDENCY_UNAVAILABLE, true, TRACE);
        RecoveryFullReconciliationResult reconciliation =
                RecoveryFullReconciliationResult.notInstalled(TRACE);

        assertEquals(false, backfill.completed());
        assertEquals(RecoveryValidationDependencyAvailability.NOT_INSTALLED,
                reconciliation.availability());
        assertEquals(false, reconciliation.qualified());
    }

    private static String id(String digit) {
        return "018f0f3e-7b2a-7cc1-8a10-" + digit.repeat(12);
    }
}
