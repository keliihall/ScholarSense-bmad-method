package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HostInputRejectionAuditServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void recordsOnlyTheNormalizedCodeWithCurrentPseudonyms() {
        var session = IdentitySession.authenticate(
                "internal-cookie-bearer", "sp_RWxQcW41M2dSeHVIZ0JpYw", "actor-pseudo",
                "browser-pseudo", "https://app.stage.invalid", "family", "digest", NOW);
        var facts = new ArrayList<IdentityAuditRecord>();
        var service = new HostInputRejectionAuditService(
                repository(session), AuditTestSupport.factory(), facts::add);

        service.record(
                session.sessionId(), "HOST_MESSAGE_REPLAYED", "ip-pseudo",
                "0123456789abcdef0123456789abcdef");

        assertEquals(1, facts.size());
        assertEquals("rejected", facts.getFirst().fact().outcome());
        assertEquals("HOST_MESSAGE_REPLAYED", facts.getFirst().fact().reasonCode());
        assertEquals("identity.host.input.reject", facts.getFirst().fact().action());
    }

    @Test
    void rejectsCodesOutsideTheFrozenHostCatalog() {
        var session = IdentitySession.authenticate(
                "internal-cookie-bearer", "sp_RWxQcW41M2dSeHVIZ0JpYw", "actor-pseudo",
                "browser-pseudo", "https://app.stage.invalid", "family", "digest", NOW);
        var service = new HostInputRejectionAuditService(
                repository(session), AuditTestSupport.factory(), ignored -> {});

        IdentityAccessException error = assertThrows(IdentityAccessException.class, () ->
                service.record(session.sessionId(), "RAW_PAYLOAD_REJECTED", "ip-pseudo",
                        "0123456789abcdef0123456789abcdef"));
        assertEquals("HOST_MESSAGE_INVALID", error.code());
    }

    private static IdentitySessionRepository repository(IdentitySession session) {
        return new IdentitySessionRepository() {
            @Override public Optional<IdentitySession> findById(String id) {
                return session.sessionId().equals(id) ? Optional.of(session) : Optional.empty();
            }
            @Override public void save(IdentitySession ignored) {}
        };
    }
}
