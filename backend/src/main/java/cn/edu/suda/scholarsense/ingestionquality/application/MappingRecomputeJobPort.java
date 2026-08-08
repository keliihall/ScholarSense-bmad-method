package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.MappingRecomputeIdentity;
import cn.edu.suda.scholarsense.ingestionquality.domain.MappingRecomputeJob;
import java.util.Optional;

public interface MappingRecomputeJobPort {
    Optional<MappingRecomputeJob> findByIdentity(MappingRecomputeIdentity identity);

    MappingRecomputeJob insertIfAbsent(MappingRecomputeJob job);
}
