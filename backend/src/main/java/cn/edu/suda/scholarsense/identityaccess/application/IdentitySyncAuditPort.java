package cn.edu.suda.scholarsense.identityaccess.application;

@FunctionalInterface
public interface IdentitySyncAuditPort {
    void append(IdentitySyncAuditEvent event);
}
