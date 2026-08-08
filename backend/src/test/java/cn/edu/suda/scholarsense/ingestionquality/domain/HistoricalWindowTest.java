package cn.edu.suda.scholarsense.ingestionquality.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HistoricalWindowTest {

    private static final String SUBJECT = "019fcfea-6000-7000-8000-000000000001";
    private static final Instant START = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-06-29T00:00:00Z");

    @Test
    void capturesTheReconstructableWindowEnvelopeAndUsesStrictActionableBoundary() {
        HistoricalWindow window = window("baseline-28d-2026-06-29", START, END,
                Instant.parse("2026-08-06T08:00:00Z"), "wm-42");

        assertEquals(ZoneId.of("Asia/Shanghai"), window.timezone());
        assertEquals(Map.of("SRC-P0-CARD-001", 3L), window.sourceVersions());
        assertEquals(Map.of("SRC-P0-CARD-001", "wm-42"), window.sourceWatermarks());
        assertEquals(List.of("QG-1.0.0"), window.qualityGateVersions());
        assertTrue(window.inputWatermarksDigest().matches("sha256:[0-9a-f]{64}"));
        assertTrue(window.isActionableAt(Instant.parse("2026-08-06T07:59:59.999999999Z")));
        assertFalse(window.isActionableAt(Instant.parse("2026-08-06T08:00:00Z")));
        assertFalse(window.isActionableAt(Instant.parse("2026-08-06T08:00:00.000000001Z")));
    }

    @Test
    void adjacentWindowsAreAllowedButOneNanosecondOverlapIsRejected() {
        HistoricalWindow first = window("baseline-28d-a", START, END,
                Instant.parse("2026-08-06T08:00:00Z"), "wm-42");
        HistoricalWindow adjacent = window("baseline-28d-b", END, END.plusSeconds(28L * 86400),
                Instant.parse("2026-09-06T08:00:00Z"), "wm-43");
        HistoricalWindow overlap = window("baseline-28d-c", END.minusNanos(1), END.plusSeconds(1),
                Instant.parse("2026-09-06T08:00:00Z"), "wm-44");

        HistoricalWindowIndex index = HistoricalWindowIndex.empty().append(first).append(adjacent);
        assertEquals(2, index.windows().size());
        assertThrows(IngestionQualityException.class,
                () -> HistoricalWindowIndex.empty().append(first).append(overlap));
    }

    @Test
    void missingApprovedBoundaryIsHistoryOnlyAndNeverTreatedAsInfinite() {
        HistoricalWindow window = window("legacy-window", START, END, null, "wm-42");

        assertFalse(window.isActionableAt(START));
    }

    private static HistoricalWindow window(
            String windowId, Instant start, Instant end, Instant latestActionableAt, String watermark) {
        return new HistoricalWindow(
                SUBJECT, windowId, start, end, ZoneId.of("Asia/Shanghai"),
                Map.of("SRC-P0-CARD-001", 3L), Map.of("SRC-P0-CARD-001", watermark),
                7, List.of("QG-1.0.0"), "personal-baseline", "1.2.0",
                "personal-baseline-28d", uuid("019fcfea-6000-7000-8000-000000000010"),
                "sha256:" + "a".repeat(64), latestActionableAt);
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
