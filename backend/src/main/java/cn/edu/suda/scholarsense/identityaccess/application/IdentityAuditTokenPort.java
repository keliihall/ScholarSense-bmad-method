package cn.edu.suda.scholarsense.identityaccess.application;

/** Authorized, keyed tokenization boundary. Implementations never expose key material. */
@FunctionalInterface
public interface IdentityAuditTokenPort {
    AuditSearchToken tokenize(AuditTokenDomain domain, String normalizedValue);

    default AuditTokenizationMetadata metadata() {
        AuditSearchToken probe = tokenize(AuditTokenDomain.ACTOR, "audit-tokenization-metadata");
        return new AuditTokenizationMetadata(probe.profileVersion(), probe.keyVersion());
    }
}
