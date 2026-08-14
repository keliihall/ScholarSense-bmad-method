package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualityEligibility;
import java.util.List;

/** Commits exact eligibility read facts atomically before a sensitive response is serialized. */
@FunctionalInterface
public interface QualityEligibilityReadAuditPort {
    void record(
            List<QualityEligibility> eligibilities,
            QualitySnapshotActorContext actor,
            String action,
            String traceId);
}
