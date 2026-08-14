package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibility;

/** Owner port reserved for Story 2.5; no Story 2.4 bean or endpoint may bind it. */
@FunctionalInterface
public interface QualityEligibilityRecoveryCommandPort {
    QualityEligibility requestRecovery(QualityEligibilityRecoveryCommand command);
}
