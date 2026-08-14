package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityReason;
import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibilityStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuleVersionIdentity;
import java.util.Objects;
import java.util.UUID;

/** Minimal locked current fact required by the latch; delivery/runtime state is excluded. */
public record QualityEligibilityCurrentState(
        UUID eligibilityId,
        RuleVersionIdentity ruleVersion,
        long aggregateVersion,
        QualityEligibilityStatus status,
        QualityEligibilityReason reason) {
    public QualityEligibilityCurrentState {
        eligibilityId = Objects.requireNonNull(eligibilityId);
        ruleVersion = Objects.requireNonNull(ruleVersion);
        if (aggregateVersion < 1 || aggregateVersion > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("INGESTION_QUALITY_ELIGIBILITY_INVALID");
        }
        status = Objects.requireNonNull(status);
        reason = Objects.requireNonNull(reason);
    }

    public String key(String registryVersion) {
        return ruleVersion.businessKey(registryVersion);
    }
}
