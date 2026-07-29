package cn.edu.suda.scholarsense.identityaccess.application;

/** Resolves the actual authorization policy and role-mapping resources in use. */
@FunctionalInterface
public interface IdentityAuthorizationPolicyAvailabilityPort {
    IdentityAuthorizationPolicySnapshot current();
}
