package cn.edu.suda.scholarsense.shared.time;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Production-safe adapter over a locally cached synchronization observation.
 * The provider must collect and authenticate evidence outside the business transaction.
 */
public final class EvidenceBoundTrustedTimeSource implements TrustedTimeSource {
    private final Clock nodeClock;
    private final String expectedSourceId;
    private final TrustedClockConstraints constraints;
    private final TimeSynchronizationStatusProvider statusProvider;
    private AcceptedTime lastAccepted;

    public EvidenceBoundTrustedTimeSource(
            Clock nodeClock,
            String expectedSourceId,
            TrustedClockConstraints constraints,
            TimeSynchronizationStatusProvider statusProvider) {
        this.nodeClock = Objects.requireNonNull(nodeClock, "nodeClock");
        if (expectedSourceId == null || !expectedSourceId.matches("[a-z][a-z0-9.-]{2,63}")) {
            throw new IllegalArgumentException("AUDIT_TIME_SOURCE_BINDING_INVALID");
        }
        this.expectedSourceId = expectedSourceId;
        this.constraints = Objects.requireNonNull(constraints, "constraints");
        this.statusProvider = Objects.requireNonNull(statusProvider, "statusProvider");
    }

    @Override
    public synchronized TrustedTime now() {
        Optional<TimeSynchronizationStatus> current;
        try {
            current = statusProvider.current();
        } catch (RuntimeException unavailable) {
            throw failure("AUDIT_TIME_SOURCE_UNAVAILABLE");
        }
        if (current == null) {
            throw failure("AUDIT_TIME_EVIDENCE_INVALID");
        }
        TimeSynchronizationStatus status = current
                .orElseThrow(() -> failure("AUDIT_TIME_SOURCE_UNAVAILABLE"));
        TimeSourceProfile profile;
        try {
            profile = status.profile();
        } catch (RuntimeException invalid) {
            throw failure("AUDIT_TIME_EVIDENCE_INVALID");
        }
        if (!expectedSourceId.equals(profile.sourceId())) {
            throw failure("AUDIT_TIME_SOURCE_UNBOUND");
        }
        if (Math.abs((long) profile.offsetMs()) > constraints.maximumSkewMs()) {
            throw failure("AUDIT_TIME_SKEW_EXCEEDED");
        }
        Instant nodeNow = nodeClock.instant();
        if (profile.observedAt().isAfter(nodeNow) || !nodeNow.isBefore(profile.freshUntil())) {
            throw failure("AUDIT_TIME_EVIDENCE_STALE");
        }
        Instant trustedNow = nodeNow.minusMillis(profile.offsetMs());
        if (lastAccepted != null
                && lastAccepted.sameObservationWindow(profile)
                && trustedNow.isBefore(lastAccepted.instant())) {
            throw failure("AUDIT_TIME_MOVED_BACKWARD");
        }
        lastAccepted = new AcceptedTime(trustedNow, profile);
        return new TrustedTime(trustedNow, profile);
    }

    private static TrustedTimeException failure(String code) {
        return new TrustedTimeException(code);
    }

    private record AcceptedTime(Instant instant, TimeSourceProfile profile) {
        boolean sameObservationWindow(TimeSourceProfile candidate) {
            return profile.sourceId().equals(candidate.sourceId())
                    && profile.profileVersion().equals(candidate.profileVersion())
                    && candidate.observedAt().isBefore(profile.freshUntil())
                    && profile.observedAt().isBefore(candidate.freshUntil());
        }
    }
}
