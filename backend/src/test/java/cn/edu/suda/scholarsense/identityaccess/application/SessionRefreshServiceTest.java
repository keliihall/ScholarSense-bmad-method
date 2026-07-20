package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import cn.edu.suda.scholarsense.identityaccess.domain.RefreshRotation;
import cn.edu.suda.scholarsense.identityaccess.domain.SessionStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SessionRefreshServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void readsServerCustodyCallsProviderOnceAndRejectsTheConcurrentOldVersion() {
        var sessions = new FakeSessions(IdentitySession.authenticate(
                "session-1", "sp_RWxQcW41M2dSeHVIZ0JpYw", "actor-pseudo", "browser-hash",
                "https://app.stage.invalid", "family-1", ContinuationService.digest("refresh-1"), NOW));
        var secrets = new FakeSecrets();
        var audit = new ArrayList<IdentityAuditRecord>();
        var providerCalls = new AtomicInteger();
        var custody = new TokenCustodyService(
                (plaintext, purpose) -> secret(plaintext),
                (encrypted, purpose) -> new String(
                        encrypted.ciphertext(), StandardCharsets.UTF_8).toCharArray(),
                secrets);
        custody.store("session-1", "school-idp", "access-1", "refresh-1", NOW.plusSeconds(300));
        RemoteIdentityProviderClient provider = new RemoteIdentityProviderClient() {
            @Override
            public RemoteRefreshTokens refresh(String registrationId, char[] refreshToken) {
                providerCalls.incrementAndGet();
                assertEquals("refresh-1", new String(refreshToken));
                return new RemoteRefreshTokens(
                        "access-2".toCharArray(), "refresh-2".toCharArray(), NOW.plusSeconds(600));
            }

            @Override
            public void revokeAndEndSession(
                    String registrationId, char[] refreshToken, SessionCommandType commandType) {}
        };
        var service = new SessionRefreshService(
                sessions, custody, provider, AuditTestSupport.factory(), audit::add, work -> work.get(),
                Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));

        var first = service.refresh(input(1));
        assertEquals(2, first.session().sessionVersion());
        assertEquals(1, providerCalls.get());
        assertEquals(ContinuationService.digest("refresh-2"), first.session().currentRefreshDigest());

        IdentityAccessException conflict = assertThrows(
                IdentityAccessException.class, () -> service.refresh(input(1)));
        assertEquals("IDENTITY_SESSION_VERSION_CONFLICT", conflict.code());
        assertEquals(1, providerCalls.get());
        assertEquals("rejected", audit.getLast().fact().outcome());
        assertEquals("IDENTITY_SESSION_VERSION_CONFLICT", audit.getLast().fact().reasonCode());
    }

    @Test
    void auditFailureAfterRemoteRotationRequiresReauthenticationAndDoesNotReturnNewTokens() {
        var sessions = sessions();
        var secrets = new FakeSecrets();
        var custody = custody(secrets);
        custody.store("session-1", "school-idp", "access-1", "refresh-1", NOW.plusSeconds(300));
        var service = new SessionRefreshService(
                sessions, custody, rotatingProvider(), AuditTestSupport.factory(), ignored -> {
                    throw new IdentityAccessException(
                            "IDENTITY_AUDIT_UNAVAILABLE", "security audit unavailable");
                }, work -> work.get(), Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));

        IdentityAccessException error = assertThrows(
                IdentityAccessException.class, () -> service.refresh(input(1)));

        assertEquals("IDENTITY_REAUTHENTICATION_REQUIRED", error.code());
        assertEquals(2, sessions.value.sessionVersion());
        assertEquals(SessionStatus.REAUTHENTICATION_REQUIRED, sessions.value.status());
        assertEquals("refresh-1", decrypt(secrets.value.refreshToken()));
    }

    @Test
    void localCommitFailureAfterRemoteRotationRollsBackAcceptedFactAndRecordsRecoveryReason() {
        var sessions = sessions();
        var secrets = new FakeSecrets();
        var custody = custody(secrets);
        custody.store("session-1", "school-idp", "access-1", "refresh-1", NOW.plusSeconds(300));
        var facts = new ArrayList<IdentityAuditRecord>();
        AtomicBoolean firstTransaction = new AtomicBoolean(true);
        RefreshTransactionPort commitFails = work -> {
            if (!firstTransaction.getAndSet(false)) {
                return work.get();
            }
            IdentitySession beforeSession = sessions.value;
            EncryptedAuthorizedClient beforeSecret = secrets.value;
            int beforeFacts = facts.size();
            work.get();
            sessions.value = beforeSession;
            secrets.value = beforeSecret;
            facts.subList(beforeFacts, facts.size()).clear();
            throw new IllegalStateException("injected local commit failure");
        };
        var service = new SessionRefreshService(
                sessions, custody, rotatingProvider(), AuditTestSupport.factory(), facts::add,
                commitFails, Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));

        IdentityAccessException error = assertThrows(
                IdentityAccessException.class, () -> service.refresh(input(1)));

        assertEquals("IDENTITY_REAUTHENTICATION_REQUIRED", error.code());
        assertEquals(2, sessions.value.sessionVersion());
        assertEquals(SessionStatus.REAUTHENTICATION_REQUIRED, sessions.value.status());
        assertEquals(1, facts.size());
        assertEquals("rejected", facts.getFirst().fact().outcome());
        assertEquals("IDENTITY_LOCAL_COMMIT_FAILED", facts.getFirst().fact().reasonCode());
    }

    @Test
    void ambiguousProviderFailureAfterRequestDispatchPersistsRecoveryRequiredAndReauthenticates() {
        var sessions = sessions();
        var secrets = new FakeSecrets();
        var custody = custody(secrets);
        custody.store("session-1", "school-idp", "access-1", "refresh-1", NOW.plusSeconds(300));
        var facts = new ArrayList<IdentityAuditRecord>();
        RemoteIdentityProviderClient ambiguousFailure = new RemoteIdentityProviderClient() {
            @Override public RemoteRefreshTokens refresh(String registrationId, char[] refreshToken) {
                throw new IllegalStateException("connection reset after request body was sent");
            }
            @Override public void revokeAndEndSession(
                    String registrationId, char[] refreshToken, SessionCommandType commandType) {}
        };
        var service = new SessionRefreshService(
                sessions, custody, ambiguousFailure, AuditTestSupport.factory(), facts::add,
                work -> work.get(), Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));

        IdentityAccessException error = assertThrows(
                IdentityAccessException.class, () -> service.refresh(input(1)));

        assertEquals("IDENTITY_REAUTHENTICATION_REQUIRED", error.code());
        assertEquals(SessionStatus.REAUTHENTICATION_REQUIRED, sessions.value.status());
        assertEquals("IDENTITY_REMOTE_PROVIDER_UNAVAILABLE", facts.getFirst().fact().reasonCode());
    }

    @Test
    void recoveryTransactionFailureStillReturnsTheBrowserSessionBlockingSignal() {
        var sessions = sessions();
        var secrets = new FakeSecrets();
        var custody = custody(secrets);
        custody.store("session-1", "school-idp", "access-1", "refresh-1", NOW.plusSeconds(300));
        var facts = new ArrayList<IdentityAuditRecord>();
        RefreshTransactionPort unavailableRecovery = new RefreshTransactionPort() {
            @Override
            public RefreshRotation execute(Supplier<RefreshRotation> work) {
                IdentitySession beforeSession = sessions.value;
                EncryptedAuthorizedClient beforeSecret = secrets.value;
                int beforeFacts = facts.size();
                work.get();
                sessions.value = beforeSession;
                secrets.value = beforeSecret;
                facts.subList(beforeFacts, facts.size()).clear();
                throw new IllegalStateException("local commit unavailable");
            }

            @Override
            public void executeRecovery(Runnable work) {
                throw new IllegalStateException("recovery transaction unavailable");
            }
        };
        var service = new SessionRefreshService(
                sessions, custody, rotatingProvider(), AuditTestSupport.factory(), facts::add,
                unavailableRecovery, Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));

        IdentityAccessException error = assertThrows(
                IdentityAccessException.class, () -> service.refresh(input(1)));

        assertEquals("IDENTITY_REAUTHENTICATION_REQUIRED", error.code());
        assertEquals(SessionStatus.ACTIVE, sessions.value.status(),
                "the HTTP adapter must invalidate the browser session when durable recovery is unavailable");
    }

    @Test
    void missingDatabaseSessionCreatesAnAnonymousRefreshRejectionFact() {
        var sessions = new FakeSessions(null);
        var facts = new ArrayList<IdentityAuditRecord>();
        var service = new SessionRefreshService(
                sessions, custody(new FakeSecrets()), rotatingProvider(), AuditTestSupport.factory(), facts::add,
                work -> work.get(), Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));

        IdentityAccessException error = assertThrows(
                IdentityAccessException.class, () -> service.refresh(input(1)));

        assertEquals("IDENTITY_SESSION_REQUIRED", error.code());
        assertEquals(cn.edu.suda.scholarsense.shared.outbox.ActorType.ANONYMOUS,
                facts.getFirst().fact().actorType());
        assertEquals("IDENTITY_SESSION_REQUIRED", facts.getFirst().fact().reasonCode());
    }

    @Test
    void serverCustodyMismatchIsACommittedRefreshReuseRejectionWithoutCallingTheProvider() {
        var session = IdentitySession.authenticate(
                "session-1", "sp_RWxQcW41M2dSeHVIZ0JpYw", "actor-pseudo", "browser-hash",
                "https://app.stage.invalid", "family-1", ContinuationService.digest("refresh-new"), NOW);
        var sessions = new FakeSessions(session);
        var secrets = new FakeSecrets();
        var custody = custody(secrets);
        custody.store("session-1", "school-idp", "access-1", "refresh-old", NOW.plusSeconds(300));
        var facts = new ArrayList<IdentityAuditRecord>();
        var providerCalls = new AtomicInteger();
        RemoteIdentityProviderClient provider = new RemoteIdentityProviderClient() {
            @Override
            public RemoteRefreshTokens refresh(String registrationId, char[] refreshToken) {
                providerCalls.incrementAndGet();
                throw new AssertionError("provider must not receive a reused refresh token");
            }

            @Override
            public void revokeAndEndSession(
                    String registrationId, char[] refreshToken, SessionCommandType commandType) {}
        };
        var service = new SessionRefreshService(
                sessions, custody, provider, AuditTestSupport.factory(), facts::add,
                work -> work.get(), Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));

        IdentityAccessException error = assertThrows(
                IdentityAccessException.class, () -> service.refresh(input(1)));

        assertEquals("IDENTITY_SESSION_EXPIRED", error.code());
        assertEquals(0, providerCalls.get());
        assertEquals("REFRESH_FAMILY_REVOKED", sessions.value.status().name());
        assertEquals("IDENTITY_TOKEN_REUSE_DETECTED", facts.getFirst().fact().reasonCode());
        assertEquals("rejected", facts.getFirst().fact().outcome());
    }

    private static SessionRefresh input(long version) {
        return new SessionRefresh(
                "session-1", version, "school-idp", "ip-pseudo",
                "0123456789abcdef0123456789abcdef");
    }

    private static FakeSessions sessions() {
        return new FakeSessions(IdentitySession.authenticate(
                "session-1", "sp_RWxQcW41M2dSeHVIZ0JpYw", "actor-pseudo", "browser-hash",
                "https://app.stage.invalid", "family-1", ContinuationService.digest("refresh-1"), NOW));
    }

    private static TokenCustodyService custody(FakeSecrets secrets) {
        return new TokenCustodyService(
                (plaintext, purpose) -> secret(plaintext),
                (encrypted, purpose) -> decrypt(encrypted).toCharArray(),
                secrets);
    }

    private static RemoteIdentityProviderClient rotatingProvider() {
        return new RemoteIdentityProviderClient() {
            @Override
            public RemoteRefreshTokens refresh(String registrationId, char[] refreshToken) {
                return new RemoteRefreshTokens(
                        "access-2".toCharArray(), "refresh-2".toCharArray(), NOW.plusSeconds(600));
            }

            @Override
            public void revokeAndEndSession(
                    String registrationId, char[] refreshToken, SessionCommandType commandType) {}
        };
    }

    private static String decrypt(EncryptedSecret encrypted) {
        return new String(encrypted.ciphertext(), StandardCharsets.UTF_8);
    }

    private static EncryptedSecret secret(char[] plaintext) {
        return new EncryptedSecret(
                new String(plaintext).getBytes(StandardCharsets.UTF_8),
                "wrapped".getBytes(StandardCharsets.UTF_8),
                "kms://stage/identity", "v1", "nonce".getBytes(StandardCharsets.UTF_8));
    }

    private static final class FakeSessions implements IdentitySessionRepository {
        private IdentitySession value;
        private FakeSessions(IdentitySession value) { this.value = value; }
        @Override public Optional<IdentitySession> findById(String ignored) { return Optional.ofNullable(value); }
        @Override public Optional<IdentitySession> findByIdForUpdate(String ignored) { return Optional.ofNullable(value); }
        @Override public void save(IdentitySession session) { value = session; }
    }

    private static final class FakeSecrets implements AuthorizedClientSecretRepository {
        private EncryptedAuthorizedClient value;
        @Override public Optional<EncryptedAuthorizedClient> find(String sessionId, String registrationId) {
            return Optional.ofNullable(value);
        }
        @Override public void save(EncryptedAuthorizedClient client) { value = client; }
    }
}
