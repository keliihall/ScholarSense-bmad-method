package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import java.time.Clock;
import java.time.Duration;

public final class HostBootstrapService {
    private static final Duration LIFETIME = Duration.ofSeconds(60);
    private final HostBootstrapRepository repository;
    private final OpaqueCodeGenerator codes;
    private final Clock clock;

    public HostBootstrapService(
            HostBootstrapRepository repository,
            OpaqueCodeGenerator codes,
            Clock clock) {
        this.repository = repository;
        this.codes = codes;
        this.clock = clock;
    }

    public HostBootstrapCreated issue(String audience, String origin, String browserBindingHash) {
        requireBinding(audience, origin, browserBindingHash);
        String code = codes.generate();
        if (code == null || !code.matches("hb_[A-Za-z0-9_-]{43,86}")) {
            throw new IdentityAccessException(
                    "IDENTITY_DEPENDENCY_UNAVAILABLE", "host bootstrap generator is unavailable");
        }
        var expiresAt = clock.instant().plus(LIFETIME);
        repository.saveBootstrap(new StoredHostBootstrap(
                ContinuationService.digest(code), audience, origin, browserBindingHash, expiresAt, null));
        return new HostBootstrapCreated(code, audience, origin, expiresAt);
    }

    public void exchange(
            String bootstrapCode,
            String audience,
            String origin,
            String browserBindingHash) {
        requireBinding(audience, origin, browserBindingHash);
        if (bootstrapCode == null || !bootstrapCode.matches("hb_[A-Za-z0-9_-]{43,86}")) {
            throw invalid("HOST_BOOTSTRAP_EXPIRED");
        }
        String digest = ContinuationService.digest(bootstrapCode);
        StoredHostBootstrap stored = repository.findBootstrapByDigest(digest)
                .orElseThrow(() -> invalid("HOST_BOOTSTRAP_EXPIRED"));
        var now = clock.instant();
        if (stored.consumedAt() != null) {
            throw invalid("HOST_BOOTSTRAP_ALREADY_USED");
        }
        if (!now.isBefore(stored.expiresAt())
                || !stored.audience().equals(audience)
                || !stored.origin().equals(origin)
                || !stored.browserBindingHash().equals(browserBindingHash)) {
            throw invalid("HOST_BOOTSTRAP_EXPIRED");
        }
        if (!repository.consumeOnce(digest, now)) {
            throw invalid("HOST_BOOTSTRAP_ALREADY_USED");
        }
    }

    private static IdentityAccessException invalid(String code) {
        return new IdentityAccessException(code, "host bootstrap is unavailable");
    }

    private static void requireBinding(String audience, String origin, String browserBindingHash) {
        if (!"scholarsense-web".equals(audience)
                || origin == null
                || !origin.matches("https://[A-Za-z0-9.-]+")
                || browserBindingHash == null
                || browserBindingHash.isBlank()) {
            throw invalid("HOST_BOOTSTRAP_EXPIRED");
        }
    }
}
