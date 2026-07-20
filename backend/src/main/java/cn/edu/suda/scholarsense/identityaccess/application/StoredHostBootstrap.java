package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;

public record StoredHostBootstrap(
        String codeDigest,
        String audience,
        String origin,
        String browserBindingHash,
        Instant expiresAt,
        Instant consumedAt) {}
