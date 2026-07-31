package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.LocalDate;

public interface ResponsibilityReconciliationPort {
    boolean terminalRunExists(CheckpointKey key, LocalDate businessDate);

    void enqueue(CheckpointKey key, LocalDate businessDate, String traceId);
}
