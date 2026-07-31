package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityRecipientValidity;
import java.time.Instant;
import java.util.Objects;

/** Minimal post-commit visibility proof returned by the public scope read model. */
public record ResponsibilityScopeReadBack(
        ResponsibilityRecipientValidity validity,
        String reasonCode,
        long sourceVersion,
        long sourceWatermark,
        long aggregateVersion,
        Instant evaluatedAt) {
    public ResponsibilityScopeReadBack {
        Objects.requireNonNull(validity, "validity");
        if (reasonCode == null
                || !reasonCode.matches("RESPONSIBILITY_[A-Z0-9_]+")
                || sourceVersion < 0
                || sourceWatermark < 0
                || aggregateVersion < 0) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SCOPE_READBACK_INVALID");
        }
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }
}
