package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.MappingRecomputeJob;
import java.util.List;

public record MappingRecomputePlan(List<MappingRecomputeJob> jobs, int historyOnlyWindowCount) {
    public MappingRecomputePlan {
        jobs = List.copyOf(jobs);
        if (historyOnlyWindowCount < 0) {
            throw new IllegalArgumentException("historyOnlyWindowCount");
        }
    }
}
