package cn.edu.suda.scholarsense.auditoperations.application;

@FunctionalInterface
public interface AuditAlertTransport {
    void send(String safeStructuredPayload);
}
