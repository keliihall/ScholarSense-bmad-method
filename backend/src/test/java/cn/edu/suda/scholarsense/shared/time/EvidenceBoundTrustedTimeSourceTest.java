package cn.edu.suda.scholarsense.shared.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EvidenceBoundTrustedTimeSourceTest {
    private static final Instant NODE_TIME = Instant.parse("2026-07-20T02:00:00Z");

    @Test
    void returnsAdjustedTimeOnlyFromFreshBoundSynchronizationEvidence() {
        var status = status(12, NODE_TIME.minusSeconds(30), NODE_TIME.plusSeconds(30));
        var source = source(status);

        TrustedTime trusted = source.now();

        assertEquals(NODE_TIME.minusMillis(12), trusted.instant());
        assertEquals("campus-ntp-a", trusted.profile().sourceId());
        assertEquals("AUDIT-CLOCK-BINDING-1.0.0", trusted.profile().profileVersion());
        assertFalse(trusted.profile().evidenceRef().contains("secret"));
    }

    @Test
    void failsClosedForMissingStaleUnboundOrOutOfSkewEvidence() {
        assertCode("AUDIT_TIME_SOURCE_UNAVAILABLE", source(Optional.empty()));
        assertCode("AUDIT_TIME_EVIDENCE_STALE", source(status(0, NODE_TIME.minusSeconds(60), NODE_TIME)));
        assertCode("AUDIT_TIME_SOURCE_UNBOUND", source(new TimeSynchronizationStatus(
                "other-ntp", "AUDIT-CLOCK-BINDING-1.0.0", 0,
                NODE_TIME.minusSeconds(1), NODE_TIME.plusSeconds(1),
                "evidence://signed/clock/other")));
        assertCode("AUDIT_TIME_SKEW_EXCEEDED", source(status(101, NODE_TIME.minusSeconds(1), NODE_TIME.plusSeconds(1))));
    }

    @Test
    void freezesPp100MaximumSkewAndNormalizesProviderFailures() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedClockConstraints("PP-1.0.0", 101));
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedClockConstraints("PP-1.0.0", 99));

        var throwsFailure = new EvidenceBoundTrustedTimeSource(
                Clock.fixed(NODE_TIME, ZoneOffset.UTC), "campus-ntp-a",
                new TrustedClockConstraints("PP-1.0.0", 100),
                () -> { throw new IllegalStateException("raw provider diagnostic"); });
        assertCode("AUDIT_TIME_SOURCE_UNAVAILABLE", throwsFailure);

        var nullOptional = new EvidenceBoundTrustedTimeSource(
                Clock.fixed(NODE_TIME, ZoneOffset.UTC), "campus-ntp-a",
                new TrustedClockConstraints("PP-1.0.0", 100), () -> null);
        assertCode("AUDIT_TIME_EVIDENCE_INVALID", nullOptional);
    }

    @Test
    void rejectsBackwardTimeOnlyInsideTheSameAcceptedObservationWindow() {
        MutableClock clock = new MutableClock(NODE_TIME);
        TimeSynchronizationStatus current = status(0, NODE_TIME.minusSeconds(10), NODE_TIME.plusSeconds(30));
        var source = new EvidenceBoundTrustedTimeSource(
                clock, "campus-ntp-a", new TrustedClockConstraints("PP-1.0.0", 100), () -> Optional.of(current));
        source.now();
        clock.instant = NODE_TIME.minusMillis(1);

        assertCode("AUDIT_TIME_MOVED_BACKWARD", source);
    }

    private static EvidenceBoundTrustedTimeSource source(TimeSynchronizationStatus status) {
        return source(Optional.of(status));
    }

    private static EvidenceBoundTrustedTimeSource source(Optional<TimeSynchronizationStatus> status) {
        return new EvidenceBoundTrustedTimeSource(
                Clock.fixed(NODE_TIME, ZoneOffset.UTC),
                "campus-ntp-a",
                new TrustedClockConstraints("PP-1.0.0", 100),
                () -> status);
    }

    private static TimeSynchronizationStatus status(int offset, Instant observedAt, Instant freshUntil) {
        return new TimeSynchronizationStatus(
                "campus-ntp-a", "AUDIT-CLOCK-BINDING-1.0.0", offset,
                observedAt, freshUntil, "evidence://signed/clock/observation-1");
    }

    private static void assertCode(String expected, TrustedTimeSource source) {
        TrustedTimeException failure = assertThrows(TrustedTimeException.class, source::now);
        assertEquals(expected, failure.code());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
