package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.HighRiskExecutionAuthorizationUseCase;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskTrustedTimePort;
import org.springframework.scheduling.annotation.Scheduled;

/** Expires abandoned execution reservations so a later call must obtain fresh signed evidence. */
public final class HighRiskExecutionLeaseExpiryScheduler {
    private final HighRiskExecutionAuthorizationUseCase authorizations;
    private final HighRiskTrustedTimePort time;

    public HighRiskExecutionLeaseExpiryScheduler(
            HighRiskExecutionAuthorizationUseCase authorizations,
            HighRiskTrustedTimePort time) {
        this.authorizations = java.util.Objects.requireNonNull(authorizations);
        this.time = java.util.Objects.requireNonNull(time);
    }

    @Scheduled(fixedDelayString =
            "${scholarsense.identity.high-risk-execution-expiry-delay-ms:30000}")
    public void expire() {
        authorizations.expireDue(time.now(), 100);
    }
}
