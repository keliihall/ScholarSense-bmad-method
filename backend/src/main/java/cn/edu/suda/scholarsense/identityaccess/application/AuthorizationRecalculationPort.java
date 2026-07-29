package cn.edu.suda.scholarsense.identityaccess.application;

/** Rebuilds authorization from authoritative server-side facts for every protected request. */
@FunctionalInterface
public interface AuthorizationRecalculationPort {
    AuthorizationDecision decide(String actorPseudonym, String sessionPseudonym);
}
