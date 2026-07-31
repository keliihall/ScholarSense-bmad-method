package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.List;
import java.util.Map;
import java.time.Instant;

public interface ResponsibilityReconciliationStorePort {
    long identityOrgWatermark(String feedId, String partitionId);

    List<ResponsibilitySnapshotEntry> actualSnapshot(
            CheckpointKey key,
            long throughWatermark,
            Instant cutoffAt,
            Map<String, Long> supportingIdentityOrgWatermarks);

    long openExceptionCount(CheckpointKey key);

    void append(
            ResponsibilityReconciliationResult result,
            ResponsibilityReconciliationLease lease);

    default List<ResponsibilityExceptionAuditTransition>
            appendWithAuditTransitions(
                    ResponsibilityReconciliationResult result,
                    ResponsibilityReconciliationLease lease) {
        append(result, lease);
        return List.of();
    }

    boolean leaseIsCurrent(ResponsibilityReconciliationLease lease);
}
