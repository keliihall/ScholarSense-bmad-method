package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppendNormalizedFactCommandTest {
    private static final UUID BATCH_ID =
            UUID.fromString("019fe570-0000-7000-8000-000000000701");
    private static final UUID LINEAGE_ID =
            UUID.fromString("019fe570-0000-7000-8000-000000000702");

    @Test
    void recordAndBusinessKeysUseTheDatabaseUnicodeScalarBoundary() {
        String exact = "\uD83D\uDE00".repeat(1024);
        AppendNormalizedFactCommand command = command(exact, exact);

        assertEquals(1024, command.recordId().codePointCount(0, command.recordId().length()));
        assertEquals(1024, command.businessKey().codePointCount(0, command.businessKey().length()));
        assertThrows(IllegalArgumentException.class,
                () -> command(exact + "x", "business"));
        assertThrows(IllegalArgumentException.class,
                () -> command("record", exact + "x"));
        assertThrows(IllegalArgumentException.class,
                () -> command("unpaired-\uD83D", "business"));
    }

    private static AppendNormalizedFactCommand command(String recordId, String businessKey) {
        return new AppendNormalizedFactCommand(
                BATCH_ID, recordId, "SRC-P0-CARD-001", businessKey, 1,
                "CARD-SLICE-1.0.0", "sha256:" + "1".repeat(64), LINEAGE_ID,
                "sha256:" + "2".repeat(64), Instant.parse("2026-08-10T00:00:00Z"));
    }
}
