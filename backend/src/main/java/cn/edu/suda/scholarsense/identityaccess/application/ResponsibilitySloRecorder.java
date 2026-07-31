package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;

@FunctionalInterface
public interface ResponsibilitySloRecorder {
    void record(
            NormalizedResponsibilityBatch batch,
            Instant appliedAt,
            IdentitySyncResult result);
}
