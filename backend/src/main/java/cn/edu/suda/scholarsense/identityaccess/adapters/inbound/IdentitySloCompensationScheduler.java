package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.IdentitySloCompensationService;
import org.springframework.scheduling.annotation.Scheduled;

public final class IdentitySloCompensationScheduler {
    private final IdentitySloCompensationService service;

    public IdentitySloCompensationScheduler(
            IdentitySloCompensationService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString =
                    "${scholarsense.identity-sync.slo-compensation-interval:30000}")
    public void recoverEvidence() {
        service.runNext();
    }
}
