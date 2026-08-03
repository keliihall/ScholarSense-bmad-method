package cn.edu.suda.scholarsense.identityaccess.api;

/** Cross-module authorization boundary. Callers must invoke it for every protected request. */
@FunctionalInterface
public interface CompositeAuthorizationPort {
    CompositeAuthorizationDecision authorize(CompositeAuthorizationRequest request);
}
