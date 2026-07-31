package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySloCompensationService;
import org.springframework.scheduling.annotation.Scheduled;

/** Replays responsibility SLO evidence writes independently of source progress. */
public final class ResponsibilitySloCompensationScheduler {
    private final ResponsibilitySloCompensationService service;

    public ResponsibilitySloCompensationScheduler(
            ResponsibilitySloCompensationService service) {
        this.service = java.util.Objects.requireNonNull(service);
    }

    @Scheduled(
            initialDelayString =
                    "${scholarsense.responsibility-slo.compensation-poll:PT30S}",
            fixedDelayString =
                    "${scholarsense.responsibility-slo.compensation-poll:PT1M}")
    public void replay() {
        service.runNext();
    }
}
