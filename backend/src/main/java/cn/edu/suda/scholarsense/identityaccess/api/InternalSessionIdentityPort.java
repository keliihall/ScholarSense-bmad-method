package cn.edu.suda.scholarsense.identityaccess.api;

/**
 * Resolves an opaque, server-owned session id without exposing servlet or identity storage types to
 * another bounded context.
 */
@FunctionalInterface
public interface InternalSessionIdentityPort {
    InternalSessionIdentity current(String internalSessionId, String sourceIp, String traceId);
}
