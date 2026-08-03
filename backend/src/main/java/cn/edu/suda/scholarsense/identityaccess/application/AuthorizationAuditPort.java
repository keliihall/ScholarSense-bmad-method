package cn.edu.suda.scholarsense.identityaccess.application;

/** Fail-closed audit boundary used before releasing authorization-sensitive content. */
@FunctionalInterface
public interface AuthorizationAuditPort {
    void record(AuthorizationAuditRequest request);
}
