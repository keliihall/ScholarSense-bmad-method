package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Instant;
import java.util.Objects;

/** Half-open authoritative validity interval: [effectiveFrom, effectiveTo). */
public record EffectiveInterval(Instant effectiveFrom, Instant effectiveTo) {
    public EffectiveInterval {
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("IDENTITY_EFFECTIVE_WINDOW_INVALID");
        }
    }

    public boolean contains(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(effectiveFrom)
                && (effectiveTo == null || instant.isBefore(effectiveTo));
    }
}
