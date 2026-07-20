package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Arrays;

/** Plaintext exists only across the provider call and immediate re-encryption boundary. */
public final class RemoteRefreshTokens implements AutoCloseable {
    private final char[] accessToken;
    private final char[] refreshToken;
    private final Instant accessExpiresAt;

    public RemoteRefreshTokens(char[] accessToken, char[] refreshToken, Instant accessExpiresAt) {
        if (accessToken == null || accessToken.length == 0
                || refreshToken == null || refreshToken.length == 0
                || accessExpiresAt == null) {
            throw new IllegalArgumentException("IDENTITY_REMOTE_REFRESH_INVALID");
        }
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.accessExpiresAt = accessExpiresAt;
    }

    char[] accessToken() { return accessToken; }
    char[] refreshToken() { return refreshToken; }
    public Instant accessExpiresAt() { return accessExpiresAt; }

    @Override
    public void close() {
        Arrays.fill(accessToken, '\0');
        Arrays.fill(refreshToken, '\0');
    }
}
