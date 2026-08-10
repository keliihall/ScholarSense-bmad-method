package cn.edu.suda.scholarsense.ingestionquality.application;

import java.util.UUID;

/** Writes one authority assertion through the dedicated authority workload connection only. */
@FunctionalInterface
public interface QualitySnapshotRetentionAuthorityIngestPort {
    UUID ingest(QualitySnapshotRetentionAuthorityEvidence evidence);
}
