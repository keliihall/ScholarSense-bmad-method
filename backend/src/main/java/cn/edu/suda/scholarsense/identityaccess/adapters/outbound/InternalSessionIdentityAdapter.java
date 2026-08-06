package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentity;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityException;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityException.Reason;
import cn.edu.suda.scholarsense.identityaccess.api.InternalSessionIdentityPort;
import cn.edu.suda.scholarsense.identityaccess.application.InternalSessionProjection;
import cn.edu.suda.scholarsense.identityaccess.application.CurrentSessionService;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import java.util.Objects;

/** Public identity adapter backed by the audited, active-session application read. */
public final class InternalSessionIdentityAdapter implements InternalSessionIdentityPort {
    private final CurrentSessionService sessions;

    public InternalSessionIdentityAdapter(CurrentSessionService sessions) {
        this.sessions = Objects.requireNonNull(sessions);
    }

    @Override
    public InternalSessionIdentity current(
            String internalSessionId, String sourceIp, String traceId) {
        if (internalSessionId == null || internalSessionId.isBlank()) {
            throw new InternalSessionIdentityException(Reason.SESSION_REQUIRED);
        }
        InternalSessionProjection value;
        try {
            value = sessions.currentInternal(internalSessionId, sourceIp, traceId);
        } catch (IdentityAccessException failure) {
            throw mapped(failure);
        } catch (RuntimeException unavailable) {
            throw new InternalSessionIdentityException(Reason.DEPENDENCY_UNAVAILABLE, unavailable);
        }
        if (value == null || !value.session().authenticated()
                || value.session().sessionPseudonym() == null
                || value.session().sessionPseudonym().isBlank()
                || value.actorPseudonym() == null || value.actorPseudonym().isBlank()) {
            throw new InternalSessionIdentityException(Reason.SESSION_REQUIRED);
        }
        return new InternalSessionIdentity(
                value.session().authenticated(), value.session().sessionPseudonym(),
                value.actorPseudonym(), value.session().sessionVersion(),
                value.session().expiresAt(), value.session().warningAt(),
                value.session().profileVersion());
    }

    private static InternalSessionIdentityException mapped(IdentityAccessException failure) {
        Reason reason = switch (failure.code()) {
            case "IDENTITY_SESSION_REQUIRED" -> Reason.SESSION_REQUIRED;
            case "IDENTITY_SESSION_EXPIRED" -> Reason.SESSION_EXPIRED;
            default -> Reason.DEPENDENCY_UNAVAILABLE;
        };
        return new InternalSessionIdentityException(reason, failure);
    }
}
