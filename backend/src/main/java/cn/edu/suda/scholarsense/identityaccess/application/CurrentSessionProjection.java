package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;

public record CurrentSessionProjection(
        boolean authenticated,
        String sessionPseudonym,
        long sessionVersion,
        Instant expiresAt,
        Instant warningAt,
        String profileVersion) {}
