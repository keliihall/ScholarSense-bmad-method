package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import java.time.Clock;
import java.time.Duration;

/** Validates application-specific OIDC claims after Spring Security verifies the JWS signature. */
public final class OidcClaimsValidator {
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final String expectedIssuer;
    private final String expectedAudience;
    private final Clock clock;

    public OidcClaimsValidator(String expectedIssuer, String expectedAudience, Clock clock) {
        this.expectedIssuer = expectedIssuer;
        this.expectedAudience = expectedAudience;
        this.clock = clock;
    }

    public void validate(OidcClaims claims, String expectedNonce) {
        var now = clock.instant();
        boolean valid = claims != null
                && expectedIssuer.equals(claims.issuer())
                && claims.audience().contains(expectedAudience)
                && expectedNonce != null
                && expectedNonce.equals(claims.nonce())
                && claims.expiresAt() != null
                && claims.expiresAt().isAfter(now.minus(CLOCK_SKEW))
                && claims.notBefore() != null
                && !claims.notBefore().isAfter(now.plus(CLOCK_SKEW))
                && claims.issuedAt() != null
                && !claims.issuedAt().isAfter(now.plus(CLOCK_SKEW))
                && claims.subject() != null
                && !claims.subject().isBlank();
        if (!valid) {
            throw new IdentityAccessException(
                    "IDENTITY_OIDC_CALLBACK_INVALID", "identity callback validation failed");
        }
    }
}
