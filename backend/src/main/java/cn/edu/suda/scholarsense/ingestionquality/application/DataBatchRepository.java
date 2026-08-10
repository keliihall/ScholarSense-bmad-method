package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.DataBatch;

public interface DataBatchRepository extends DataBatchReadPort {
    void insert(DataBatch batch);

    void save(DataBatch batch, long expectedVersion);
}
