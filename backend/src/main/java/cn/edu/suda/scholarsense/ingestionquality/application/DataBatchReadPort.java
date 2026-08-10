package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.BatchIdentity;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatch;
import java.util.Optional;
import java.util.UUID;

/** Read-only hydration boundary for owner-persisted data batches. */
public interface DataBatchReadPort {
    Optional<DataBatch> find(UUID batchId);

    Optional<DataBatch> findByIdentity(BatchIdentity identity);

    Optional<DataBatch> latestForBusinessKey(String sourceId, String businessKey);

    Optional<DataBatch> lineageHead(UUID lineageId);
}
