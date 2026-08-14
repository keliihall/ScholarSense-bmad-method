package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibility;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QualityEligibilityQueryPort {
    List<QualityEligibility> findCurrent(QualityEligibilityQueryCriteria criteria);

    Optional<QualityEligibility> findCurrentById(UUID eligibilityId);
}
