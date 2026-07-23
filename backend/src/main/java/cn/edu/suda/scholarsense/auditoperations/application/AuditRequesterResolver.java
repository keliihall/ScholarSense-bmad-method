package cn.edu.suda.scholarsense.auditoperations.application;

@FunctionalInterface
public interface AuditRequesterResolver {
    String currentSessionPseudonym(String requesterKey);
}
