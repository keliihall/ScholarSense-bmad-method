package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import java.nio.charset.StandardCharsets;
import java.nio.CharBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.Set;

public final class ContinuationService {
    private static final Set<String> ALLOWED_ROUTES = Set.of("shell.home", "shell.session");
    private static final Duration LIFETIME = Duration.ofMinutes(5);

    private final ContinuationRepository repository;
    private final OpaqueCodeGenerator codes;
    private final Clock clock;

    public ContinuationService(ContinuationRepository repository, OpaqueCodeGenerator codes, Clock clock) {
        this.repository = repository;
        this.codes = codes;
        this.clock = clock;
    }

    public ContinuationCreated create(
            String browserSessionId, String origin, String routeId, String opaqueContext) {
        if (!ALLOWED_ROUTES.contains(routeId) || !safeBinding(browserSessionId) || !safeOrigin(origin)
                || (opaqueContext != null && !opaqueContext.matches("[A-Za-z0-9_-]{1,128}"))) {
            throw invalid();
        }
        String code = codes.generate();
        if (code == null || !code.matches("ct_[A-Za-z0-9_-]{43,86}")) {
            throw new IdentityAccessException(
                    "IDENTITY_DEPENDENCY_UNAVAILABLE", "continuation generator is unavailable");
        }
        Instant expiresAt = clock.instant().plus(LIFETIME);
        repository.save(new StoredContinuation(
                digest(code), browserSessionId, origin, routeId, opaqueContext, expiresAt, null));
        return new ContinuationCreated(code, expiresAt);
    }

    public ContinuationTarget consume(String code, String browserSessionId, String origin) {
        if (code == null || !code.matches("ct_[A-Za-z0-9_-]{43,86}")) {
            throw invalid();
        }
        StoredContinuation stored = repository.findByDigest(digest(code)).orElseThrow(ContinuationService::invalid);
        Instant now = clock.instant();
        if (stored.consumedAt() != null
                || !now.isBefore(stored.expiresAt())
                || !stored.browserSessionId().equals(browserSessionId)
                || !stored.origin().equals(origin)
                || !ALLOWED_ROUTES.contains(stored.routeId())) {
            throw invalid();
        }
        repository.markConsumed(stored.codeDigest(), now);
        return new ContinuationTarget(stored.routeId(), stored.opaqueContext());
    }

    public static String digest(String value) {
        try {
            byte[] result = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(result);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static String digest(char[] value) {
        var encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(encoded);
            byte[] result = digest.digest();
            return HexFormat.of().formatHex(result);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        } finally {
            if (encoded.hasArray()) {
                Arrays.fill(encoded.array(), (byte) 0);
            }
        }
    }

    private static boolean safeBinding(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    private static boolean safeOrigin(String value) {
        return value != null && value.matches("https://[A-Za-z0-9.-]+");
    }

    private static IdentityAccessException invalid() {
        return new IdentityAccessException(
                "CONTINUATION_INVALID_OR_EXPIRED", "the requested destination is unavailable");
    }
}
