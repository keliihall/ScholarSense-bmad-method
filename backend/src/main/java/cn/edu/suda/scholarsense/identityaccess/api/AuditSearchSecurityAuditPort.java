package cn.edu.suda.scholarsense.identityaccess.api;

/** Records audit-search rejections that occur before the MVC controller is reached. */
@FunctionalInterface
public interface AuditSearchSecurityAuditPort {
    void recordRejected(String requesterKey, String reasonCode, String traceId);
}
