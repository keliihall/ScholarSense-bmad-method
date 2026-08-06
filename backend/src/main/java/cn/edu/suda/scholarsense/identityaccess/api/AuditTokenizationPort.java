package cn.edu.suda.scholarsense.identityaccess.api;

/** Current-key, server-only audit tokenization boundary. Raw values never leave the caller. */
@FunctionalInterface
public interface AuditTokenizationPort {
    AuditTokenizedValue tokenize(AuditTokenizationDomain domain, String normalizedValue);
}
