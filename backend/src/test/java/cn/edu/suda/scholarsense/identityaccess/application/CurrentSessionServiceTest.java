package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CurrentSessionServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void recalculatesAuthorizationAndReturnsProjectionOnlyAfterAuditTransactionCompletes() {
        var calls = new AtomicInteger();
        var commits = new AtomicInteger();
        var audit = new ArrayList<IdentityAuditRecord>();
        var session = IdentitySession.authenticate(
                "internal-cookie-bearer", "sp_RWxQcW41M2dSeHVIZ0JpYw", "actor-pseudo", "browser-hash",
                "https://app.stage.invalid", "family", "digest", NOW);
        var service = new CurrentSessionService(
                new IdentitySessionRepository() {
                    @Override public Optional<IdentitySession> findById(String ignored) {
                        return Optional.of(session);
                    }
                    @Override public void save(IdentitySession ignored) {}
                },
                (actor, identitySession) -> calls.incrementAndGet() <= 1,
                AuditTestSupport.factory(),
                audit::add,
                new SensitiveReadTransactionPort() {
                    @Override
                    public <T> T execute(java.util.function.Supplier<T> work) {
                        T result = work.get();
                        commits.incrementAndGet();
                        return result;
                    }
                },
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));

        var projection = service.current(
                session.sessionId(), "192.0.2.10", "0123456789abcdef0123456789abcdef");
        assertEquals(1, projection.sessionVersion());
        assertEquals("sp_RWxQcW41M2dSeHVIZ0JpYw", projection.sessionPseudonym());
        assertEquals(1, commits.get());
        assertEquals("identity.session.view", audit.getFirst().fact().action());
        assertEquals("accepted", audit.getFirst().fact().outcome());
        IdentityAccessException error = assertThrows(
                IdentityAccessException.class,
                () -> service.current(
                        session.sessionId(), "192.0.2.10", "1123456789abcdef0123456789abcdef"));
        assertEquals("IDENTITY_SESSION_REQUIRED", error.code());
        assertEquals(2, calls.get());
        assertEquals(2, commits.get(), "denial fact commits before the stable error is raised");
        assertEquals("rejected", audit.getLast().fact().outcome());
        assertEquals("IDENTITY_SESSION_REQUIRED", audit.getLast().fact().reasonCode());
    }

    @Test
    void auditOrCommitFailureNeverReturnsSensitiveProjection() {
        var session = IdentitySession.authenticate(
                "internal-cookie-bearer", "sp_RWxQcW41M2dSeHVIZ0JpYw", "actor-pseudo", "browser-hash",
                "https://app.stage.invalid", "family", "digest", NOW);
        var service = new CurrentSessionService(
                repository(session),
                (actor, identitySession) -> true,
                AuditTestSupport.factory(),
                ignored -> { throw new IdentityAccessException(
                        "IDENTITY_AUDIT_UNAVAILABLE", "security audit unavailable"); },
                directTransaction(),
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));

        IdentityAccessException auditFailure = assertThrows(
                IdentityAccessException.class,
                () -> service.current(
                        session.sessionId(), "192.0.2.10", "2123456789abcdef0123456789abcdef"));
        assertEquals("IDENTITY_AUDIT_UNAVAILABLE", auditFailure.code());

        var commitFailure = new CurrentSessionService(
                repository(session),
                (actor, identitySession) -> true,
                AuditTestSupport.factory(),
                ignored -> {},
                new SensitiveReadTransactionPort() {
                    @Override
                    public <T> T execute(java.util.function.Supplier<T> work) {
                        work.get();
                        throw new IdentityAccessException(
                                "IDENTITY_AUDIT_UNAVAILABLE", "security audit commit failed");
                    }
                },
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));
        assertEquals(
                "IDENTITY_AUDIT_UNAVAILABLE",
                assertThrows(IdentityAccessException.class, () -> commitFailure.current(
                        session.sessionId(), "192.0.2.10", "3123456789abcdef0123456789abcdef")).code());
    }

    @Test
    void unavailableAuditLedgerBlocksBeforeSensitiveReadTransaction() {
        var transactions = new AtomicInteger();
        var service = new CurrentSessionService(
                repository(IdentitySession.authenticate(
                        "internal-cookie-bearer", "sp_RWxQcW41M2dSeHVIZ0JpYw", "actor-pseudo",
                        "browser-hash", "https://app.stage.invalid", "family", "digest", NOW)),
                (actor, session) -> true,
                AuditTestSupport.factory(),
                ignored -> {},
                new SensitiveReadTransactionPort() {
                    @Override public <T> T execute(java.util.function.Supplier<T> work) {
                        transactions.incrementAndGet();
                        return work.get();
                    }
                },
                Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC),
                traceId -> { throw new IdentityAccessException(
                        "AUDIT_AVAILABILITY_UNAVAILABLE", "audit evidence is unavailable"); });

        IdentityAccessException failure = assertThrows(IdentityAccessException.class, () -> service.current(
                "internal-cookie-bearer", "192.0.2.10", "4123456789abcdef0123456789abcdef"));

        assertEquals("AUDIT_AVAILABILITY_UNAVAILABLE", failure.code());
        assertEquals(0, transactions.get());
    }

    private static IdentitySessionRepository repository(IdentitySession session) {
        return new IdentitySessionRepository() {
            @Override public Optional<IdentitySession> findById(String ignored) { return Optional.of(session); }
            @Override public void save(IdentitySession ignored) {}
        };
    }

    private static SensitiveReadTransactionPort directTransaction() {
        return new SensitiveReadTransactionPort() {
            @Override public <T> T execute(java.util.function.Supplier<T> work) { return work.get(); }
        };
    }
}
