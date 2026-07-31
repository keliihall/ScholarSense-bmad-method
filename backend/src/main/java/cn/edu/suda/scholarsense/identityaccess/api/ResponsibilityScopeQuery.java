package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.Objects;

public record ResponsibilityScopeQuery(
        String studentSourceRefDigest, Instant serverNow) {
    public ResponsibilityScopeQuery {
        if (studentSourceRefDigest == null
                || !studentSourceRefDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SCOPE_STUDENT_REF_INVALID");
        }
        Objects.requireNonNull(serverNow, "serverNow");
    }
}
