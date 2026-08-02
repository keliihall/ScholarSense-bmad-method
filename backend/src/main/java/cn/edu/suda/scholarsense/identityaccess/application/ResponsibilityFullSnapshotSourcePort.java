package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.LocalDate;

@FunctionalInterface
public interface ResponsibilityFullSnapshotSourcePort {
    ResponsibilityFullSnapshot fetch(
            CheckpointKey key,
            LocalDate businessDate,
            String traceId);

    default ResponsibilityFullSnapshot fetchVersion(
            CheckpointKey key,
            LocalDate businessDate,
            String contractVersion,
            String traceId) {
        if (!ResponsibilityAuthoritySourcePort.VERSION_1.equals(
                contractVersion)) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_SOURCE_CONTRACT_UNAPPROVED");
        }
        return fetch(key, businessDate, traceId);
    }
}
