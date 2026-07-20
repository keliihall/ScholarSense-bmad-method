package cn.edu.suda.scholarsense.shared.time;

import java.time.Instant;
import java.util.Objects;

public record TimeSourceProfile(
        String sourceId,
        String profileVersion,
        int offsetMs,
        Instant observedAt,
        Instant freshUntil,
        String evidenceRef) {
    public TimeSourceProfile {
        requireCode(sourceId, "sourceId", "[a-z][a-z0-9.-]{2,63}");
        if (!"AUDIT-CLOCK-BINDING-1.0.0".equals(profileVersion)) {
            throw new IllegalArgumentException("AUDIT_TIME_PROFILE_INVALID");
        }
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(freshUntil, "freshUntil");
        if (!freshUntil.isAfter(observedAt)) {
            throw new IllegalArgumentException("AUDIT_TIME_WINDOW_INVALID");
        }
        requireCode(
                evidenceRef,
                "evidenceRef",
                "evidence://signed/[A-Za-z0-9._/-]{3,240}");
    }

    private static void requireCode(String value, String field, String pattern) {
        if (value == null || !value.matches(pattern)) {
            throw new IllegalArgumentException("AUDIT_TIME_" + field.toUpperCase() + "_INVALID");
        }
    }
}
