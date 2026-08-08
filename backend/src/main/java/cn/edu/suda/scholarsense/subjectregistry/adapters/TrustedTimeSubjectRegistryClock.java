package cn.edu.suda.scholarsense.subjectregistry.adapters;

import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/** UTC clock facade preserving the shared trusted-time evidence boundary. */
final class TrustedTimeSubjectRegistryClock extends Clock {
    private final TrustedTimeSource time;

    TrustedTimeSubjectRegistryClock(TrustedTimeSource time) {
        this.time = Objects.requireNonNull(time);
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override public Clock withZone(ZoneId zone) {
        if (!ZoneOffset.UTC.equals(zone)) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_CLOCK_ZONE_INVALID");
        }
        return this;
    }

    @Override public Instant instant() { return time.now().instant(); }
}
