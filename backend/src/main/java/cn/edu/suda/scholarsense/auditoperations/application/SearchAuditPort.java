package cn.edu.suda.scholarsense.auditoperations.application;

@FunctionalInterface
public interface SearchAuditPort {
    void commit(SearchAuditEvent event);
}
