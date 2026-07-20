package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;

public record EncryptedAuthorizedClient(
        String sessionId,
        String registrationId,
        EncryptedSecret accessToken,
        EncryptedSecret refreshToken,
        Instant accessExpiresAt) {}
