package cn.edu.suda.scholarsense.auditoperations.application;

@FunctionalInterface
public interface AuditArchiveSelectionPort {
    AuditArchiveSelection select(long sequenceStart, long sequenceEnd);
}
