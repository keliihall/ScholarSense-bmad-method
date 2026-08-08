package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.subjectregistry.domain.MappingCorrectionEvent;

public interface SubjectRegistryOutboxPort {
    void appendCorrection(MappingCorrectionEvent event);
    void appendRecomputeRequest(MappingRecomputeRequestIntent request);
}
