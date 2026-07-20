package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Arrays;
import java.util.function.Function;

public final class TokenCustodyService {
    private final EnvelopeEncryptionPort encryption;
    private final EnvelopeDecryptionPort decryption;
    private final AuthorizedClientSecretRepository repository;

    public TokenCustodyService(
            EnvelopeEncryptionPort encryption,
            AuthorizedClientSecretRepository repository) {
        this(encryption, (encrypted, purpose) -> {
            throw new IllegalStateException("IDENTITY_KMS_DECRYPT_UNAVAILABLE");
        }, repository);
    }

    public TokenCustodyService(
            EnvelopeEncryptionPort encryption,
            EnvelopeDecryptionPort decryption,
            AuthorizedClientSecretRepository repository) {
        this.encryption = encryption;
        this.decryption = decryption;
        this.repository = repository;
    }

    public void store(
            String sessionId,
            String registrationId,
            String accessToken,
            String refreshToken,
            Instant accessExpiresAt) {
        char[] access = requiredSecret(accessToken);
        char[] refresh = requiredSecret(refreshToken);
        try {
            store(sessionId, registrationId, access, refresh, accessExpiresAt);
        } finally {
            Arrays.fill(access, '\0');
            Arrays.fill(refresh, '\0');
        }
    }

    public void store(
            String sessionId,
            String registrationId,
            char[] accessToken,
            char[] refreshToken,
            Instant accessExpiresAt) {
        requiredSecret(accessToken);
        requiredSecret(refreshToken);
        EncryptedSecret encryptedAccess = encryption.encrypt(accessToken, "oidc-access-token");
        EncryptedSecret encryptedRefresh = encryption.encrypt(refreshToken, "oidc-refresh-token");
        repository.save(new EncryptedAuthorizedClient(
                sessionId, registrationId, encryptedAccess, encryptedRefresh, accessExpiresAt));
    }

    public <T> T withRefreshToken(
            String sessionId,
            String registrationId,
            Function<char[], T> work) {
        EncryptedAuthorizedClient client = repository.find(sessionId, registrationId).orElseThrow(() ->
                new cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException(
                        "IDENTITY_SESSION_EXPIRED", "session is no longer refreshable"));
        char[] plaintext = decryption.decrypt(client.refreshToken(), "oidc-refresh-token");
        try {
            return work.apply(plaintext);
        } finally {
            Arrays.fill(plaintext, '\0');
        }
    }

    private static char[] requiredSecret(String value) {
        if (value == null || value.isBlank() || value.length() > 16_384) {
            throw new IllegalArgumentException("IDENTITY_TOKEN_INVALID");
        }
        return value.toCharArray();
    }

    private static void requiredSecret(char[] value) {
        if (value == null || value.length == 0 || value.length > 16_384) {
            throw new IllegalArgumentException("IDENTITY_TOKEN_INVALID");
        }
    }
}
