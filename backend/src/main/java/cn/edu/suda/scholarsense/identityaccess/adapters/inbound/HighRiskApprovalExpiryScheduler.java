package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.HighRiskApprovalUseCase;
import cn.edu.suda.scholarsense.identityaccess.application.HighRiskTrustedTimePort;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded expiry pass; database CAS makes overlapping scheduler runs harmless. */
public final class HighRiskApprovalExpiryScheduler {
    private final HighRiskApprovalUseCase approvals;
    private final HighRiskTrustedTimePort time;

    public HighRiskApprovalExpiryScheduler(
            HighRiskApprovalUseCase approvals, HighRiskTrustedTimePort time) {
        this.approvals = java.util.Objects.requireNonNull(approvals);
        this.time = java.util.Objects.requireNonNull(time);
    }

    @Scheduled(fixedDelayString = "${scholarsense.identity.high-risk-expiry-delay-ms:30000}")
    public void expire() {
        approvals.expireDue(time.now(), 100);
    }
}
