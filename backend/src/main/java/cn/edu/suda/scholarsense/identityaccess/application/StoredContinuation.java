package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;

public record StoredContinuation(
        String codeDigest,
        String browserSessionId,
        String origin,
        String routeId,
        String opaqueContext,
        Instant expiresAt,
        Instant consumedAt) {

    public StoredContinuation consume(Instant at) {
        return new StoredContinuation(
                codeDigest, browserSessionId, origin, routeId, opaqueContext, expiresAt, at);
    }
}
