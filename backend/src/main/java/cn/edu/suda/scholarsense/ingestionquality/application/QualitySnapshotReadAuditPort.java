package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.ingestionquality.domain.QualitySnapshot;

/** Sensitive detail reads must commit this owner-local fact before serialization. */
@FunctionalInterface
public interface QualitySnapshotReadAuditPort {
    void record(
            QualitySnapshot snapshot,
            QualitySnapshotActorContext actor,
            String action,
            String traceId);
}
