package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.List;

public record OidcClaims(
        String issuer,
        List<String> audience,
        String nonce,
        Instant expiresAt,
        Instant notBefore,
        Instant issuedAt,
        String subject) {

    public OidcClaims {
        audience = audience == null ? List.of() : List.copyOf(audience);
    }
}
