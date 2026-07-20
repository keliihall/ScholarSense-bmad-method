package cn.edu.suda.scholarsense.shared.time;

import java.time.Instant;
import java.util.Objects;

public record TrustedTime(Instant instant, TimeSourceProfile profile) {
    public TrustedTime {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(profile, "profile");
    }
}
