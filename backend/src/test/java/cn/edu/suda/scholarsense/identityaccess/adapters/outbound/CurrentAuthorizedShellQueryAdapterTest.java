package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapability;
import cn.edu.suda.scholarsense.identityaccess.api.AuthorizedShellCapabilityState;
import cn.edu.suda.scholarsense.identityaccess.api.AuthoritativeIdentityContext;
import cn.edu.suda.scholarsense.identityaccess.api.IdentityFreshness;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySessionRepository;
import cn.edu.suda.scholarsense.identityaccess.application.SensitiveReadTransactionPort;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import cn.edu.suda.scholarsense.identityaccess.domain.RoleFieldPolicyCatalog;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CurrentAuthorizedShellQueryAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void everyReadRecomposesCurrentSessionIdentityAndProviderCapabilities() {
        IdentitySession session = IdentitySession.authenticate(
                "session-id",
                "sp_" + "a".repeat(32),
                "actor-pseudonym",
                "browser-binding",
                "https://app.test.invalid",
                "refresh-family",
                "refresh-digest",
                NOW);
        var adapter = new CurrentAuthorizedShellQueryAdapter(
                repository(session),
                actor -> Optional.of(context()),
                List.of(
                        () -> List.of(new AuthorizedShellCapability(
                                "identity-session",
                                "当前会话",
                                "shell.session",
                                AuthorizedShellCapabilityState.AVAILABLE,
                                Set.of("R1-COUNSELOR", "R7-PLATFORM-OPS"))),
                        () -> List.of(new AuthorizedShellCapability(
                                "audit-search",
                                "审计检索",
                                "audit.search",
                                AuthorizedShellCapabilityState.AVAILABLE,
                                Set.of("R3-STUDENT-AFFAIRS", "R7-PLATFORM-OPS")))),
                CurrentAuthorizedShellQueryAdapterTest::trustedTime,
                RoleFieldPolicyCatalog.approved(),
                ignored -> {},
                directTransaction());

        var projection = adapter.current("session-id", "0".repeat(32), "192.0.2.1");

        assertEquals("care-workbench", projection.defaultSurface().surfaceId());
        assertEquals(List.of("identity-session", "audit-search"),
                projection.menuItems().stream().map(item -> item.id()).toList());
        assertEquals(NOW, projection.evaluatedAt());
    }

    @Test
    void auditFailurePreventsTheAuthorizedShellFromBeingReleased() {
        IdentitySession session = IdentitySession.authenticate(
                "session-id", "sp_" + "a".repeat(32), "actor-pseudonym",
                "browser-binding", "https://app.test.invalid", "refresh-family",
                "refresh-digest", NOW);
        var adapter = new CurrentAuthorizedShellQueryAdapter(
                repository(session), actor -> Optional.of(context()), List.of(),
                CurrentAuthorizedShellQueryAdapterTest::trustedTime,
                RoleFieldPolicyCatalog.approved(),
                ignored -> { throw new IllegalStateException("injected audit failure"); },
                directTransaction());

        assertThrows(RuntimeException.class, () ->
                adapter.current("session-id", "0".repeat(32), "192.0.2.1"));
    }

    @Test
    void malformedDuplicateProviderFailsClosedAndAuditsDependency() {
        IdentitySession session = IdentitySession.authenticate(
                "session-id", "sp_" + "a".repeat(32), "actor-pseudonym",
                "browser-binding", "https://app.test.invalid", "refresh-family",
                "refresh-digest", NOW);
        var audits = new java.util.ArrayList<
                cn.edu.suda.scholarsense.identityaccess.application.AuthorizationAuditRequest>();
        var duplicate = new AuthorizedShellCapability(
                "duplicate-capability", "重复能力", "shell.session",
                AuthorizedShellCapabilityState.AVAILABLE,
                Set.of("R1-COUNSELOR"));
        var adapter = new CurrentAuthorizedShellQueryAdapter(
                repository(session), actor -> Optional.of(context()),
                List.of(() -> List.of(duplicate), () -> List.of(duplicate)),
                CurrentAuthorizedShellQueryAdapterTest::trustedTime,
                RoleFieldPolicyCatalog.approved(), audits::add, directTransaction());

        assertThrows(RuntimeException.class, () ->
                adapter.current("session-id", "0".repeat(32), "192.0.2.1"));
        assertEquals(1, audits.size());
        assertEquals("DEPENDENCY_UNAVAILABLE", audits.getFirst().result());
    }

    private static SensitiveReadTransactionPort directTransaction() {
        return new SensitiveReadTransactionPort() {
            @Override
            public <T> T execute(java.util.function.Supplier<T> work) {
                return work.get();
            }
        };
    }

    private static IdentitySessionRepository repository(IdentitySession session) {
        return new IdentitySessionRepository() {
            @Override
            public Optional<IdentitySession> findById(String sessionId) {
                return Optional.of(session);
            }

            @Override
            public void save(IdentitySession ignored) {}
        };
    }

    private static AuthoritativeIdentityContext context() {
        return new AuthoritativeIdentityContext(
                UUID.fromString("019c1234-0000-7000-8000-000000000701"),
                List.of("R7-PLATFORM-OPS", "R1-COUNSELOR"),
                List.of(UUID.fromString("019c1234-0000-7000-8000-000000000702")),
                3,
                7,
                7,
                IdentityFreshness.FRESH,
                Map.of(
                        "identitySessionPolicy", "ISP-1.0.0",
                        "roleFieldPolicy", "RFP-1.0.0",
                        "roleMapping", "IDENTITY-ROLE-MAPPING-1.0.0",
                        "roleMappingDigest", "sha256:" + "a".repeat(64)),
                NOW);
    }

    private static TrustedTime trustedTime() {
        return new TrustedTime(
                NOW,
                new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(10),
                        "evidence://signed/clock/campus-ntp-a.json"));
    }
}
