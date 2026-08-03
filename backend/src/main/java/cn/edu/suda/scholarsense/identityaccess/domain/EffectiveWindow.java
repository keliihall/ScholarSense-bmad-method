package cn.edu.suda.scholarsense.identityaccess.domain;

import java.time.Instant;
import java.util.Objects;

public record EffectiveWindow(Instant startAt, Instant endAt) {
    public EffectiveWindow {
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        if (!startAt.isBefore(endAt)) {
            throw new IllegalArgumentException("IDENTITY_AUTHORIZATION_WINDOW_INVALID");
        }
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(startAt) && instant.isBefore(endAt);
    }
}
