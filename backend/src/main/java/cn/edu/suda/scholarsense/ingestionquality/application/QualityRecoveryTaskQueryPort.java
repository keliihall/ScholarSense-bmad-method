package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QualityRecoveryTaskQueryPort {
    List<QualityRecoveryTask> findCurrent(QualityRecoveryTaskQueryCriteria criteria);
    Optional<QualityRecoveryTask> findCurrentById(UUID taskId);
}
