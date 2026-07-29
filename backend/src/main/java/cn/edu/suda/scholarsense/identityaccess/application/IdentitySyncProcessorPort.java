package cn.edu.suda.scholarsense.identityaccess.application;

@FunctionalInterface
public interface IdentitySyncProcessorPort {
    IdentitySyncResult process(
            NormalizedIdentityBatch batch,
            IdentityLease lease,
            java.util.function.Consumer<IdentitySyncResult> afterApply);
}
