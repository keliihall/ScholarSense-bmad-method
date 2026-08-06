package cn.edu.suda.scholarsense.ingestionquality.adapters;

import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/** Clock facade that preserves the shared evidence-bound trusted-time failure semantics. */
final class TrustedTimeClock extends Clock {
    private final TrustedTimeSource time;

    TrustedTimeClock(TrustedTimeSource time) {
        this.time = Objects.requireNonNull(time);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (!ZoneOffset.UTC.equals(zone)) {
            throw new IllegalArgumentException("INGESTION_QUALITY_CLOCK_ZONE_INVALID");
        }
        return this;
    }

    @Override
    public Instant instant() {
        return time.now().instant();
    }
}
