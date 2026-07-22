package cn.edu.suda.scholarsense.auditoperations.api;

@FunctionalInterface
public interface AuditProducerBacklogPort {
    AuditProducerBacklogSnapshot current();
}
