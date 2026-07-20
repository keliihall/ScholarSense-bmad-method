package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RemoteLogoutProcessorTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void makesPartialIdpFailureVisibleAndRetriesWithoutTouchingTheLocalSession() {
        var repository = new FakeWork();
        var processor = new RemoteLogoutProcessor(
                repository,
                custody(),
                provider((registration, refresh, type) -> {
                    throw new IllegalStateException("raw provider diagnostic");
                }),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertTrue(processor.processOne());

        assertEquals("IDENTITY_REMOTE_REVOCATION_UNAVAILABLE", repository.safeCode);
        assertEquals(NOW.plusSeconds(120), repository.nextAttempt);
        assertEquals(0, repository.confirmed);
    }

    @Test
    void confirmsOnlyAfterProviderSuccessAndErasesTheDecryptedRefreshToken() {
        var repository = new FakeWork();
        var observed = new AtomicReference<char[]>();
        var processor = new RemoteLogoutProcessor(
                repository,
                custody(),
                provider((registration, refresh, type) -> {
                    assertEquals("school-idp", registration);
                    assertEquals("refresh-secret", new String(refresh));
                    observed.set(refresh);
                }),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertTrue(processor.processOne());

        assertEquals(1, repository.confirmed);
        assertTrue(new String(observed.get()).chars().allMatch(value -> value == 0));
    }

    private static TokenCustodyService custody() {
        byte[] refresh = "refresh-secret".getBytes(StandardCharsets.UTF_8);
        EncryptedAuthorizedClient stored = new EncryptedAuthorizedClient(
                "session-1", "school-idp",
                secret("access".getBytes(StandardCharsets.UTF_8)), secret(refresh), NOW.plusSeconds(60));
        AuthorizedClientSecretRepository secrets = new AuthorizedClientSecretRepository() {
            @Override public Optional<EncryptedAuthorizedClient> find(String sessionId, String registrationId) {
                return Optional.of(stored);
            }
            @Override public void save(EncryptedAuthorizedClient client) {}
        };
        return new TokenCustodyService(
                (plaintext, purpose) -> secret("unused".getBytes(StandardCharsets.UTF_8)),
                (encrypted, purpose) -> new String(
                        encrypted.ciphertext(), StandardCharsets.UTF_8).toCharArray(),
                secrets);
    }

    private static EncryptedSecret secret(byte[] ciphertext) {
        return new EncryptedSecret(
                ciphertext, "wrapped".getBytes(StandardCharsets.UTF_8),
                "kms://stage/identity", "v1", "nonce".getBytes(StandardCharsets.UTF_8));
    }

    private static RemoteIdentityProviderClient provider(Revocation revocation) {
        return new RemoteIdentityProviderClient() {
            @Override public RemoteRefreshTokens refresh(String registrationId, char[] refreshToken) {
                throw new UnsupportedOperationException();
            }
            @Override public void revokeAndEndSession(
                    String registrationId, char[] refreshToken, SessionCommandType type) {
                revocation.revoke(registrationId, refreshToken, type);
            }
        };
    }

    @FunctionalInterface
    private interface Revocation {
        void revoke(String registrationId, char[] refreshToken, SessionCommandType type);
    }

    private static final class FakeWork implements RemoteLogoutWorkRepository {
        private String safeCode;
        private Instant nextAttempt;
        private int confirmed;
        private boolean claimed;
        @Override public Optional<StoredRemoteLogout> claimDue(Instant now) {
            if (claimed) return Optional.empty();
            claimed = true;
            return Optional.of(new StoredRemoteLogout(
                    "018f7b87-ee53-7942-9aec-d5948b86b811", "session-1", "school-idp",
                    SessionCommandType.ACCOUNT_SWITCH, 1, now));
        }
        @Override public void markConfirmed(String requestId, Instant completedAt) { confirmed++; }
        @Override public void markRetry(String requestId, String code, Instant at) {
            safeCode = code;
            nextAttempt = at;
        }
    }
}
