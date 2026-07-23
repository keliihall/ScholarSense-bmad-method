package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.List;

@FunctionalInterface
public interface AuditSearchTokenGateway {
    List<AuditTokenQueryValue> query(AuditTokenQueryRequest request);
}
