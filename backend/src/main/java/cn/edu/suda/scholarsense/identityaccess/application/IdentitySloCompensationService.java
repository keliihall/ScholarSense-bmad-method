package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;

/** Retries durable SLO evidence writes without losing them from the denominator. */
public final class IdentitySloCompensationService {
    private final IdentitySloEvidencePort evidence;
    private final TrustedTimeSource time;

    public IdentitySloCompensationService(
            IdentitySloEvidencePort evidence, TrustedTimeSource time) {
        this.evidence = evidence;
        this.time = time;
    }

    public boolean runNext() {
        var pending = evidence.nextCompensation();
        if (pending.isEmpty()) {
            return false;
        }
        IdentitySloEvidence item = pending.get();
        evidence.append(item);
        evidence.completeCompensation(item.evidenceId(), time.now().instant());
        return true;
    }
}
