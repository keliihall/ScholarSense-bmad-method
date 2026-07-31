package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import java.time.Instant;
import java.util.List;

@FunctionalInterface
public interface ResponsibilitySyncTransactionPort {
    void apply(
            CheckpointKey key,
            List<AuthoritativeResponsibilityRelation> relations,
            long expectedWatermark,
            IdentityLease lease,
            Instant appliedAt,
            String traceId);
}
