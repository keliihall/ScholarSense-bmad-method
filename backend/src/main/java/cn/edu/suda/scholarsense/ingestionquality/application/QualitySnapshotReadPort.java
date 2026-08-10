package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;
import java.util.Optional;
import java.util.UUID;

/** Read-only hydration boundary for immutable quality snapshots. */
public interface QualitySnapshotReadPort {
    Optional<QualitySnapshot> findByBatchId(UUID batchId);
}
