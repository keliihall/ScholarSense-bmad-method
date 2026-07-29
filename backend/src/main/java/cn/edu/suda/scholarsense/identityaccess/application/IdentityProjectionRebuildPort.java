package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.List;

public interface IdentityProjectionRebuildPort {
    List<IdentityArchivedEnvelope> loadArchives(CheckpointKey key);

    void replaceCurrentProjection(
            CheckpointKey key,
            List<NormalizedIdentityBatch> batches,
            String traceId,
            Instant rebuiltAt);
}
