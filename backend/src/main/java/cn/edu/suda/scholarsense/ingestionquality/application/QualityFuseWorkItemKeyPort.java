package cn.edu.suda.scholarsense.ingestionquality.application;

/** Deployment-owned HMAC boundary used only when a new fuse episode is created. */
@FunctionalInterface
public interface QualityFuseWorkItemKeyPort {
    QualityFuseWorkItemIdentity current(
            String sourceId, String dependencyId, long episodeGeneration);
}
