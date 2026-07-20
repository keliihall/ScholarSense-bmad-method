package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;

/** Opaque, short-lived proof issued to the authenticated iframe for its exact portal host. */
public record HostBootstrapCreated(
        String bootstrapCode,
        String audience,
        String origin,
        Instant expiresAt) {}
