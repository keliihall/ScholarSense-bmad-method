package cn.edu.suda.scholarsense.identityaccess.application;

/** Identity-access-owned atomic fact + audit-outbox write port. */
@FunctionalInterface
public interface IdentityAuditPort {
    void append(IdentityAuditRecord record);
}
