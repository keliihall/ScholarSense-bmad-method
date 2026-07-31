package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.LocalDate;

@FunctionalInterface
public interface ResponsibilityFullSnapshotSourcePort {
    ResponsibilityFullSnapshot fetch(
            CheckpointKey key,
            LocalDate businessDate,
            String traceId);
}
