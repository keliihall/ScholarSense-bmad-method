package cn.edu.suda.scholarsense.auditoperations.application;

@FunctionalInterface
public interface AuditSearchAuthorizationGateway {
    AuthorizedAuditSearchDecision authorize(AuthorizedAuditSearchRequest request);
}
