package cn.edu.suda.scholarsense.identityaccess.api;

import java.time.Instant;

/** Public immutable session summary; identity application and storage types stay private. */
public record CurrentIdentitySession(
        boolean authenticated,
        String sessionPseudonym,
        long sessionVersion,
        Instant expiresAt,
        Instant warningAt,
        String profileVersion) {}
