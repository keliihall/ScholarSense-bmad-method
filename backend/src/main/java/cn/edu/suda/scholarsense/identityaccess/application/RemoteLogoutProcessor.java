package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Clock;
import java.time.Duration;

/** Converges external revocation after local fail-closed revocation; it never reactivates a session. */
public final class RemoteLogoutProcessor {
    private final RemoteLogoutWorkRepository work;
    private final TokenCustodyService tokenCustody;
    private final RemoteIdentityProviderClient identityProvider;
    private final Clock clock;

    public RemoteLogoutProcessor(
            RemoteLogoutWorkRepository work,
            TokenCustodyService tokenCustody,
            RemoteIdentityProviderClient identityProvider,
            Clock clock) {
        this.work = work;
        this.tokenCustody = tokenCustody;
        this.identityProvider = identityProvider;
        this.clock = clock;
    }

    public boolean processOne() {
        var request = work.claimDue(clock.instant()).orElse(null);
        if (request == null) return false;
        try {
            tokenCustody.withRefreshToken(
                    request.sessionId(),
                    request.registrationId(),
                    refreshToken -> {
                        identityProvider.revokeAndEndSession(
                                request.registrationId(), refreshToken, request.commandType());
                        return null;
                    });
            work.markConfirmed(request.requestId(), clock.instant());
        } catch (RuntimeException unavailable) {
            long exponent = Math.min(request.attempts(), 6);
            work.markRetry(
                    request.requestId(), "IDENTITY_REMOTE_REVOCATION_UNAVAILABLE",
                    clock.instant().plus(Duration.ofMinutes(1L << exponent)));
        }
        return true;
    }
}
