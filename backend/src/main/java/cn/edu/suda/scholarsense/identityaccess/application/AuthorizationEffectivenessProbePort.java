package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.Optional;

@FunctionalInterface
public interface AuthorizationEffectivenessProbePort {
    Optional<AuthorizationEffectiveContext> findCurrent(String subjectBindingToken);
}
