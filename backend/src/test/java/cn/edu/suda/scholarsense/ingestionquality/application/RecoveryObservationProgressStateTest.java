package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryObservationProgressStateTest {
    private static final UUID RECOVERY_ID =
            UUID.fromString("018f34c0-9b80-7a11-8abc-0123456789ab");
    private static final Instant START = Instant.parse("2026-08-14T00:00:00Z");

    @Test
    void publishedPairsAdvanceBusinessSequenceAndFailureRelapsesSameGeneration() {
        RecoveryObservationProgressState initial = RecoveryObservationProgressState.start(
                RECOVERY_ID, 3, "SRC-P0-CAMPUS-ACCESS-001",
                "DEP-P0-CAMPUS-ACCESS-001", START);

        RecoveryObservationProgressState first = initial.passed(
                7, 0, "wm-7", START.plusSeconds(60));
        RecoveryObservationProgressState second = first.passed(
                8, 0, "wm-8", START.plusSeconds(120));
        RecoveryObservationProgressState relapsed = second.relapsed(
                9, 0, "wm-9", START.plusSeconds(180));

        assertEquals(2, second.consecutivePassedBatches());
        assertEquals(8, second.lastSourceVersionOrdinal());
        assertEquals(RecoveryObservationProgressStatus.RELAPSED, relapsed.status());
        assertEquals(3, relapsed.generation());
        assertEquals(3, relapsed.aggregateVersion());
    }

    @Test
    void gapAndLateFenceAreRejectedRatherThanGuessedFromTimeOrWatermark() {
        RecoveryObservationProgressState initial = RecoveryObservationProgressState.start(
                RECOVERY_ID, 1, "SRC-P0-CAMPUS-ACCESS-001",
                "DEP-P0-CAMPUS-ACCESS-001", START)
                .passed(7, 0, "opaque-z", START.plusSeconds(60));

        assertThrows(IllegalArgumentException.class, () -> initial.passed(
                9, 0, "opaque-a", START.plusSeconds(120)));
        assertThrows(IllegalArgumentException.class, () -> initial.passed(
                8, 0, "opaque-a", START.plusSeconds(30)));
    }

    @Test
    void aNewPairAfterReadyReopensObservationWithoutLosingTheGeneration() {
        RecoveryObservationProgressState ready = new RecoveryObservationProgressState(
                RECOVERY_ID, 3, "SRC-P0-CAMPUS-ACCESS-001",
                "DEP-P0-CAMPUS-ACCESS-001", START,
                7, 0, 3, "wm-7", START.plusSeconds(3600),
                RecoveryObservationProgressStatus.READY, 4);

        RecoveryObservationProgressState reopened = ready.passed(
                8, 0, "wm-8", START.plusSeconds(3660));

        assertEquals(RecoveryObservationProgressStatus.OBSERVING, reopened.status());
        assertEquals(3, reopened.generation());
        assertEquals(5, reopened.aggregateVersion());
    }

    @Test
    void directCorrectionAtTheSameOrdinalCanRelapseTheObservation() {
        RecoveryObservationProgressState passed = RecoveryObservationProgressState.start(
                        RECOVERY_ID, 3, "SRC-P0-CAMPUS-ACCESS-001",
                        "DEP-P0-CAMPUS-ACCESS-001", START)
                .passed(7, 0, "wm-7", START.plusSeconds(60));

        RecoveryObservationProgressState corrected = passed.relapsed(
                7, 1, "wm-7-correction", START.plusSeconds(120));

        assertEquals(RecoveryObservationProgressStatus.RELAPSED, corrected.status());
        assertEquals(7, corrected.lastSourceVersionOrdinal());
        assertEquals(1, corrected.lastLineageRevision());
    }
}
