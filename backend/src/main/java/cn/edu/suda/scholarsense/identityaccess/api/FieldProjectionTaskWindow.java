package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.Objects;

public record FieldProjectionTaskWindow(Instant startAt, Instant endAt) {
    public FieldProjectionTaskWindow {
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        if (!startAt.isBefore(endAt)) {
            throw new IllegalArgumentException("FIELD_PROJECTION_TASK_WINDOW_INVALID");
        }
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(startAt) && instant.isBefore(endAt);
    }
}
