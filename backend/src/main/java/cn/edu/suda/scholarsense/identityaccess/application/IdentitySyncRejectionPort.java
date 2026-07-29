package cn.edu.suda.scholarsense.identityaccess.application;

@FunctionalInterface
public interface IdentitySyncRejectionPort {
    void reject(IdentitySyncRejection rejection);

    default boolean leaseIsCurrent(IdentityLease lease) {
        return true;
    }
}
