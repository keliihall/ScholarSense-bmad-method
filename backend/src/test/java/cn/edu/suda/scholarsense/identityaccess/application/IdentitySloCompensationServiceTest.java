package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentitySloCompensationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-24T00:02:00Z");

    @Test
    void retriesPendingEvidenceAndMarksItCompleted() {
        IdentitySloEvidence pending = new IdentitySloEvidence(
                UUID.fromString("019c1234-0000-7000-8000-000000000801"),
                null,
                IdentityRecordKind.ORGANIZATION,
                7,
                7,
                3,
                NOW.minusSeconds(120),
                NOW.minusSeconds(30),
                NOW,
                false,
                "IDENTITY_AUTHORIZATION_READBACK_EMPTY",
                "0123456789abcdef0123456789abcdef");
        var port = new PendingPort(pending);
        var service = new IdentitySloCompensationService(
                port,
                () -> new TrustedTime(
                        NOW,
                        new TimeSourceProfile(
                                "campus-ntp-a",
                                "AUDIT-CLOCK-BINDING-1.0.0",
                                5,
                                NOW.minusSeconds(10),
                                NOW.plusSeconds(50),
                                "evidence://signed/clock/campus-ntp-a.json")));

        assertTrue(service.runNext());
        assertEquals(pending, port.appended);
        assertEquals(pending.evidenceId(), port.completed);
        assertFalse(service.runNext());
    }

    private static final class PendingPort implements IdentitySloEvidencePort {
        private IdentitySloEvidence pending;
        private IdentitySloEvidence appended;
        private UUID completed;

        private PendingPort(IdentitySloEvidence pending) {
            this.pending = pending;
        }

        @Override
        public void append(IdentitySloEvidence evidence) {
            appended = evidence;
        }

        @Override
        public Optional<IdentitySloEvidence> nextCompensation() {
            return Optional.ofNullable(pending);
        }

        @Override
        public void completeCompensation(UUID evidenceId, Instant completedAt) {
            completed = evidenceId;
            pending = null;
        }
    }
}
