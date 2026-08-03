package cn.edu.suda.scholarsense.identityaccess.api;

/** Call immediately before a protected state change commits. */
@FunctionalInterface
public interface CompositeAuthorizationRecheckPort {
    CompositeAuthorizationRecheckDecision recheck(CompositeAuthorizationRecheckRequest request);
}
