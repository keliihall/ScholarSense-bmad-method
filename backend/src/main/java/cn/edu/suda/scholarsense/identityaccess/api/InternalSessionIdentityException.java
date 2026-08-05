package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.Objects;

/** Stable cross-context failure taxonomy for server-owned session resolution. */
public final class InternalSessionIdentityException extends RuntimeException {
    public enum Reason {
        SESSION_REQUIRED,
        SESSION_EXPIRED,
        DEPENDENCY_UNAVAILABLE
    }

    private final Reason reason;

    public InternalSessionIdentityException(Reason reason) {
        this(reason, null);
    }

    public InternalSessionIdentityException(Reason reason, Throwable cause) {
        super(Objects.requireNonNull(reason).name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
