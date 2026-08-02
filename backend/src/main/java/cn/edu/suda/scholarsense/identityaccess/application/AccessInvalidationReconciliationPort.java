package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReconciliation;
import java.time.Instant;

@FunctionalInterface
public interface AccessInvalidationReconciliationPort {
    AccessInvalidationReconciliation reconcile(
            AccessInvalidationLineageId lineageId,
            long targetVersion,
            Instant checkedAt,
            String traceId);
}
