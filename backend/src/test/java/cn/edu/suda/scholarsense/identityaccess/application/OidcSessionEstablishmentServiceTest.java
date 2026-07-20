package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class OidcSessionEstablishmentServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void commitsPseudonymousSessionEncryptedTokensAndAuditTogether() {
        var repository = new FakeRepository();
        var encrypted = new ArrayList<EncryptedAuthorizedClient>();
        var audit = new ArrayList<IdentityAuditRecord>();
        var service = new OidcSessionEstablishmentService(
                repository,
                new TokenCustodyService((plaintext, purpose) -> new EncryptedSecret(
                        ("cipher-" + purpose).getBytes(StandardCharsets.UTF_8),
                        "wrapped".getBytes(StandardCharsets.UTF_8), "kms://stage/identity", "v3",
                        "nonce".getBytes(StandardCharsets.UTF_8)), encrypted::add),
                AuditTestSupport.factory(),
                audit::add,
                Runnable::run,
                (purpose, raw) -> purpose + "-pseudonym",
                () -> "rf_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ",
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.establish(input());

        IdentitySession session = repository.value.orElseThrow();
        assertEquals("sp_" + ContinuationService.digest("identity-session-pseudonym"),
                session.sessionPseudonym());
        assertEquals("identity-actor-pseudonym", session.actorPseudonym());
        assertEquals("browser-binding-pseudonym", session.browserBindingHash());
        assertEquals(1, encrypted.size());
        assertEquals(1, audit.size());
        String rendered = encrypted.getFirst().toString() + audit.getFirst().toString();
        assertFalse(rendered.contains("access-plaintext"));
        assertFalse(rendered.contains("refresh-plaintext"));
        assertFalse(rendered.contains("subject-raw"));
    }

    @Test
    void doesNotPublishAuditWhenEncryptedCustodyFails() {
        var repository = new FakeRepository();
        var audit = new ArrayList<IdentityAuditRecord>();
        var service = new OidcSessionEstablishmentService(
                repository,
                new TokenCustodyService((plaintext, purpose) -> {
                    throw new IllegalStateException("KMS_UNAVAILABLE");
                }, ignored -> {}),
                AuditTestSupport.factory(),
                audit::add,
                work -> {
                    repository.value = Optional.empty();
                    try { work.run(); } catch (RuntimeException failure) {
                        repository.value = Optional.empty();
                        throw failure;
                    }
                },
                (purpose, raw) -> purpose + "-pseudonym",
                () -> "rf_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ",
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(IllegalStateException.class, () -> service.establish(input()));
        assertEquals(Optional.empty(), repository.value);
        assertEquals(List.of(), audit);
    }

    @Test
    void consumesContinuationInsideTheSameAtomicSessionTokenAndAuditBoundary() {
        AtomicBoolean insideTransaction = new AtomicBoolean();
        var repository = new FakeRepository();
        var continuationRepository = new ContinuationRepository() {
            private StoredContinuation stored = new StoredContinuation(
                    ContinuationService.digest("ct_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"),
                    "browser-raw", "https://app.stage.invalid", "shell.session", null,
                    NOW.plusSeconds(300), null);
            @Override public void save(StoredContinuation value) { stored = value; }
            @Override public Optional<StoredContinuation> findByDigest(String digest) {
                return stored.codeDigest().equals(digest) ? Optional.of(stored) : Optional.empty();
            }
            @Override public void markConsumed(String digest, Instant consumedAt) {
                if (!insideTransaction.get()) {
                    throw new AssertionError("continuation must be consumed in establishment transaction");
                }
                stored = new StoredContinuation(
                        stored.codeDigest(), stored.browserSessionId(), stored.origin(), stored.routeId(),
                        stored.opaqueContext(), stored.expiresAt(), consumedAt);
            }
        };
        var continuations = new ContinuationService(
                continuationRepository, () -> "ct_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ",
                Clock.fixed(NOW, ZoneOffset.UTC));
        IdentityEstablishmentTransactionPort transaction = work -> {
            insideTransaction.set(true);
            try { work.run(); } finally { insideTransaction.set(false); }
        };
        var service = new OidcSessionEstablishmentService(
                repository,
                new TokenCustodyService((plaintext, purpose) -> new EncryptedSecret(
                        ("cipher-" + purpose).getBytes(StandardCharsets.UTF_8),
                        "wrapped".getBytes(StandardCharsets.UTF_8), "kms://stage/identity", "v3",
                        "nonce".getBytes(StandardCharsets.UTF_8)), ignored -> {}),
                AuditTestSupport.factory(), ignored -> {}, transaction,
                (purpose, raw) -> purpose + "-pseudonym",
                () -> "rf_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ",
                Clock.fixed(NOW, ZoneOffset.UTC));

        ContinuationTarget target = service.establishAndConsume(
                input(), continuations, "ct_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ");

        assertEquals("shell.session", target.routeId());
        assertFalse(insideTransaction.get());
        assertEquals(true, repository.value.isPresent());
    }

    private static OidcSessionEstablishment input() {
        return new OidcSessionEstablishment(
                "session-1", "https://idp.stage.invalid", "subject-raw", "school-idp",
                "access-plaintext", "refresh-plaintext", NOW.plusSeconds(300),
                "browser-raw", "https://app.stage.invalid", "192.0.2.10",
                "0123456789abcdef0123456789abcdef");
    }

    private static final class FakeRepository implements IdentitySessionRepository {
        private Optional<IdentitySession> value = Optional.empty();
        @Override public Optional<IdentitySession> findById(String ignored) { return value; }
        @Override public void save(IdentitySession session) { value = Optional.of(session); }
    }
}
