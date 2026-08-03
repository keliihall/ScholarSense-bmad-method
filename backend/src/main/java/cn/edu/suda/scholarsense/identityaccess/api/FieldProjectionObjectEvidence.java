package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Facts supplied by the server-side object owner, never by a projection HTTP payload. */
public record FieldProjectionObjectEvidence(
        String purpose,
        Instant serverNow,
        Optional<FieldProjectionTaskWindow> taskWindow,
        boolean currentWorkItem,
        boolean assigned,
        boolean ownedSource,
        Set<String> fieldAllowlist,
        Optional<Set<String>> delegationFieldAllowlist,
        String keyStateVersion) {
    public FieldProjectionObjectEvidence {
        if (purpose == null || !purpose.matches("[a-z][a-z0-9.-]{2,127}")) {
            throw new IllegalArgumentException("FIELD_PROJECTION_PURPOSE_INVALID");
        }
        Objects.requireNonNull(serverNow, "serverNow");
        taskWindow = Objects.requireNonNull(taskWindow, "taskWindow");
        fieldAllowlist = Set.copyOf(fieldAllowlist);
        delegationFieldAllowlist = Objects.requireNonNull(delegationFieldAllowlist, "delegationFieldAllowlist")
                .map(Set::copyOf);
        if (keyStateVersion == null || !keyStateVersion.matches("[a-z0-9][a-z0-9._-]{2,63}")) {
            throw new IllegalArgumentException("FIELD_PROJECTION_KEY_STATE_VERSION_INVALID");
        }
    }
}
