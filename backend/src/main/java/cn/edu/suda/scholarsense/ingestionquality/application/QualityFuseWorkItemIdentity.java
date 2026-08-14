package cn.edu.suda.scholarsense.ingestionquality.application;

/** Opaque public-task identity plus the protected key version pinned to its episode. */
public record QualityFuseWorkItemIdentity(String workItemKey, String keyVersion) {
    public QualityFuseWorkItemIdentity {
        if (workItemKey == null || !workItemKey.matches("^qf:[0-9a-f]{64}$")
                || keyVersion == null || !keyVersion.matches("^k[1-9][0-9]*$")) {
            throw new IllegalArgumentException(
                    "INGESTION_QUALITY_FUSE_WORK_ITEM_IDENTITY_INVALID");
        }
    }
}
