package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;

public record OidcSessionEstablishment(
        String sessionId,
        String issuer,
        String subject,
        String registrationId,
        String accessToken,
        String refreshToken,
        Instant accessExpiresAt,
        String browserBinding,
        String origin,
        String sourceIp,
        String traceId) {}
