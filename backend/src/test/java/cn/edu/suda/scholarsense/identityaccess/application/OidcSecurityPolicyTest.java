package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class OidcSecurityPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void generatesTheRfc7636S256Challenge() {
        assertEquals(
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                PkceProof.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void validatesIssuerAudienceNonceAndSixtySecondTimeSkew() {
        OidcClaimsValidator validator = new OidcClaimsValidator(
                "https://idp.stage.invalid", "scholarsense-client", Clock.fixed(NOW, ZoneOffset.UTC));
        OidcClaims valid = new OidcClaims(
                "https://idp.stage.invalid", List.of("scholarsense-client"), "nonce-hash",
                NOW.plusSeconds(300), NOW.plusSeconds(60), NOW.minusSeconds(60), "subject-pseudo");

        validator.validate(valid, "nonce-hash");

        IdentityAccessException error = assertThrows(IdentityAccessException.class, () -> validator.validate(
                new OidcClaims("https://lookalike.invalid", valid.audience(), valid.nonce(),
                        valid.expiresAt(), valid.notBefore(), valid.issuedAt(), valid.subject()),
                "nonce-hash"));
        assertEquals("IDENTITY_OIDC_CALLBACK_INVALID", error.code());
    }

    @Test
    void requiresExactUnsafeOriginOrSameOriginRefererAndFrozenCookieAttributes() {
        RequestOriginPolicy policy = new RequestOriginPolicy("https://app.stage.invalid");
        policy.requireAllowed("https://app.stage.invalid", null);
        policy.requireAllowed(null, "https://app.stage.invalid/scholarsense/session");
        assertThrows(IdentityAccessException.class, () ->
                policy.requireAllowed("https://evil.stage.invalid", "https://app.stage.invalid/path"));

        String cookie = SessionCookiePolicy.render("opaque-session");
        assertTrue(cookie.startsWith("__Host-ScholarSense=opaque-session;"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertTrue(cookie.contains("Path=/"));
        assertTrue(!cookie.contains("Domain="));
    }
}
