package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;

@FunctionalInterface
public interface AuditCenterIngressPort {
    AuditCenterIngressResult ingest(LocalAuditOutboxRecord source);
}
