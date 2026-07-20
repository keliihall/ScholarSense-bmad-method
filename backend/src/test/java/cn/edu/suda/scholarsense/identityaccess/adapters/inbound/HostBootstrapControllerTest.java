package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.identityaccess.application.HostBootstrapRepository;
import cn.edu.suda.scholarsense.identityaccess.application.HostBootstrapService;
import cn.edu.suda.scholarsense.identityaccess.application.StoredHostBootstrap;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

class HostBootstrapControllerTest {
    private static final String APP = "https://app.stage.invalid";
    private static final String PORTAL = "https://portal.stage.invalid";
    private static final String CODE = "hb_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";

    @Test
    void sameOriginIframeIssuesAndConsumesAProofBoundToThePortalAndBrowserSession() {
        var repository = new Repository();
        var service = new HostBootstrapService(
                repository, () -> CODE,
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC));
        var issuance = new HostBootstrapIssuanceController(service, APP, PORTAL);
        var exchange = new HostBootstrapController(service, APP, PORTAL);
        var request = new MockHttpServletRequest("POST", "/api/v1/host-bootstrap-issuances");
        request.setSession(new MockHttpSession(null, "browser-session"));

        var challenge = issuance.issue(APP, request);
        assertEquals(CODE, challenge.get("bootstrapCode"));
        exchange.exchange(
                new HostBootstrapController.BootstrapExchangeRequest(CODE, "scholarsense-web", PORTAL),
                APP,
                request);

        IdentityAccessException replay = assertThrows(IdentityAccessException.class, () ->
                exchange.exchange(
                        new HostBootstrapController.BootstrapExchangeRequest(
                                CODE, "scholarsense-web", PORTAL),
                        APP,
                        request));
        assertEquals("HOST_BOOTSTRAP_ALREADY_USED", replay.code());
    }

    @Test
    void neverTreatsThePortalOriginAsTheIframeBffRequestOrigin() {
        var service = new HostBootstrapService(
                new Repository(), () -> CODE, Clock.systemUTC());
        var issuance = new HostBootstrapIssuanceController(service, APP, PORTAL);
        var request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());

        IdentityAccessException rejected = assertThrows(
                IdentityAccessException.class, () -> issuance.issue(PORTAL, request));

        assertEquals("HOST_ORIGIN_FORBIDDEN", rejected.code());
    }

    private static final class Repository implements HostBootstrapRepository {
        private StoredHostBootstrap value;
        @Override public void saveBootstrap(StoredHostBootstrap bootstrap) { value = bootstrap; }
        @Override public Optional<StoredHostBootstrap> findBootstrapByDigest(String digest) {
            return value != null && value.codeDigest().equals(digest)
                    ? Optional.of(value) : Optional.empty();
        }
        @Override public boolean consumeOnce(String digest, Instant consumedAt) {
            if (value == null || value.consumedAt() != null || !value.codeDigest().equals(digest)) return false;
            value = new StoredHostBootstrap(
                    value.codeDigest(), value.audience(), value.origin(), value.browserBindingHash(),
                    value.expiresAt(), consumedAt);
            return true;
        }
    }
}
