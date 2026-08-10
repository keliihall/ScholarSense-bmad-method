package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;

public interface DataBatchAuditPort {
    void requireHealthy(String traceId, Instant at);
    void append(DataBatchAuditEvent event);
}
