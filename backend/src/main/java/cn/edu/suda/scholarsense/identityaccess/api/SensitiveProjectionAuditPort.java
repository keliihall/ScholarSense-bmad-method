package cn.edu.suda.scholarsense.identityaccess.api;

@FunctionalInterface
public interface SensitiveProjectionAuditPort {
    void record(SensitiveProjectionAuditRecord record);
}
