package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;

/** Replays persisted SLO write failures without changing source checkpoints. */
public final class ResponsibilitySloCompensationService {
    private final ResponsibilitySloEvidencePort evidence;
    private final TrustedTimeSource time;

    public ResponsibilitySloCompensationService(
            ResponsibilitySloEvidencePort evidence,
            TrustedTimeSource time) {
        this.evidence = java.util.Objects.requireNonNull(evidence);
        this.time = java.util.Objects.requireNonNull(time);
    }

    public boolean runNext() {
        var pending = evidence.nextCompensation();
        if (pending.isEmpty()) {
            return false;
        }
        ResponsibilitySloEvidence item = pending.get();
        evidence.append(item);
        evidence.completeCompensation(
                item.evidenceId(), time.now().instant());
        return true;
    }
}
