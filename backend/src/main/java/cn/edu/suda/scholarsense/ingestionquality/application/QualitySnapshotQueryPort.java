package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QualitySnapshotQueryPort {
    List<QualitySnapshot> findAssessed(QualitySnapshotQueryCriteria criteria);

    Optional<QualitySnapshot> findById(UUID snapshotId);
}
