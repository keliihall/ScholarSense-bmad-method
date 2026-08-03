package cn.edu.suda.scholarsense.shared.outbox;

@FunctionalInterface
public interface AuditProducerBacklogPort {
    AuditProducerBacklogSnapshot current();
}
