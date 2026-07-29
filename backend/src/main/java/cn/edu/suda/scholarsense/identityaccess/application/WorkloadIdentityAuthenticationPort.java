package cn.edu.suda.scholarsense.identityaccess.application;

/** Returns a short-lived authorization header; callers must never persist or log it. */
@FunctionalInterface
public interface WorkloadIdentityAuthenticationPort {
    String authorizationHeader(String workloadIdentityReference);
}
