package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResponsibilitySloCompensationServiceTest {
    private static final Instant NOW =
            Instant.parse("2026-07-30T00:15:01Z");

    @Test
    void replaysPersistedFailureAndMarksItCompleted() {
        var port = new FakePort();
        var service = new ResponsibilitySloCompensationService(
                port,
                () -> new TrustedTime(
                        NOW,
                        new TimeSourceProfile(
                                "campus-ntp-a",
                                "AUDIT-CLOCK-BINDING-1.0.0",
                                5,
                                NOW.minusSeconds(10),
                                NOW.plusSeconds(50),
                                "evidence://signed/clock/test.json")));

        assertTrue(service.runNext());
        assertEquals(port.pending.evidenceId(), port.appended);
        assertEquals(port.pending.evidenceId(), port.completed);
    }

    private static final class FakePort
            implements ResponsibilitySloEvidencePort {
        private final ResponsibilitySloEvidence pending =
                new ResponsibilitySloEvidence(
                        UUID.fromString(
                                "019c1234-0000-7000-8000-000000000703"),
                        UUID.fromString(
                                "019c1234-0000-7000-8000-000000000704"),
                        "a".repeat(64),
                        7,
                        7,
                        7,
                        NOW.minusSeconds(901),
                        NOW.minusSeconds(1),
                        NOW,
                        false,
                        "RESPONSIBILITY_SLO_EVIDENCE_WRITE_FAILED",
                        "0123456789abcdef0123456789abcdef");
        private UUID appended;
        private UUID completed;

        @Override
        public void append(ResponsibilitySloEvidence evidence) {
            appended = evidence.evidenceId();
        }

        @Override
        public Optional<ResponsibilitySloEvidence>
                nextCompensation() {
            return Optional.of(pending);
        }

        @Override
        public void completeCompensation(
                UUID evidenceId, Instant completedAt) {
            completed = evidenceId;
            assertEquals(NOW, completedAt);
        }
    }
}
