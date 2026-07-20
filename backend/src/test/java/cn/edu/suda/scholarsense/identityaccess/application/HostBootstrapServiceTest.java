package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HostBootstrapServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");
    private static final String CODE = "hb_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";

    @Test
    void consumesOnlyOnceWithExactAudienceOriginAndBrowserBinding() {
        var repository = new FakeRepository(new StoredHostBootstrap(
                ContinuationService.digest(CODE), "scholarsense-web", "https://portal.stage.invalid",
                "browser-binding", NOW.plusSeconds(60), null));
        var service = service(repository);

        service.exchange(CODE, "scholarsense-web", "https://portal.stage.invalid", "browser-binding");
        IdentityAccessException replay = assertThrows(IdentityAccessException.class, () ->
                service.exchange(CODE, "scholarsense-web", "https://portal.stage.invalid", "browser-binding"));
        assertEquals("HOST_BOOTSTRAP_ALREADY_USED", replay.code());
    }

    @Test
    void doesNotRevealWhichBindingWasWrong() {
        var repository = new FakeRepository(new StoredHostBootstrap(
                ContinuationService.digest(CODE), "scholarsense-web", "https://portal.stage.invalid",
                "browser-binding", NOW.plusSeconds(60), null));
        var service = service(repository);
        IdentityAccessException rejected = assertThrows(IdentityAccessException.class, () ->
                service.exchange(CODE, "other", "https://evil.invalid", "wrong"));
        assertEquals("HOST_BOOTSTRAP_EXPIRED", rejected.code());
    }

    @Test
    void issuesAndConsumesOneProofForTheExactBrowserPortalAndAudience() {
        var repository = new FakeRepository(null);
        var service = service(repository);

        HostBootstrapCreated created = service.issue(
                "scholarsense-web", "https://portal.stage.invalid", "browser-binding");

        assertEquals(CODE, created.bootstrapCode());
        assertEquals(NOW.plusSeconds(60), created.expiresAt());
        service.exchange(CODE, created.audience(), created.origin(), "browser-binding");
        IdentityAccessException replay = assertThrows(IdentityAccessException.class, () ->
                service.exchange(CODE, created.audience(), created.origin(), "browser-binding"));
        assertEquals("HOST_BOOTSTRAP_ALREADY_USED", replay.code());
    }

    @Test
    void failsClosedWhenTheOpaqueCodeGeneratorReturnsAnInvalidValue() {
        var repository = new FakeRepository(null);
        var service = new HostBootstrapService(
                repository, () -> "predictable", Clock.fixed(NOW, ZoneOffset.UTC));

        IdentityAccessException rejected = assertThrows(IdentityAccessException.class, () ->
                service.issue("scholarsense-web", "https://portal.stage.invalid", "browser-binding"));

        assertEquals("IDENTITY_DEPENDENCY_UNAVAILABLE", rejected.code());
        assertEquals(Optional.empty(), repository.findBootstrapByDigest(ContinuationService.digest("predictable")));
    }

    private static HostBootstrapService service(FakeRepository repository) {
        return new HostBootstrapService(
                repository, () -> CODE, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class FakeRepository implements HostBootstrapRepository {
        private StoredHostBootstrap value;
        private FakeRepository(StoredHostBootstrap value) { this.value = value; }
        @Override public void saveBootstrap(StoredHostBootstrap bootstrap) { value = bootstrap; }
        @Override public Optional<StoredHostBootstrap> findBootstrapByDigest(String digest) {
            return value != null && value.codeDigest().equals(digest) ? Optional.of(value) : Optional.empty();
        }
        @Override public boolean consumeOnce(String digest, Instant consumedAt) {
            if (value.consumedAt() != null) return false;
            value = new StoredHostBootstrap(value.codeDigest(), value.audience(), value.origin(),
                    value.browserBindingHash(), value.expiresAt(), consumedAt);
            return true;
        }
    }
}
