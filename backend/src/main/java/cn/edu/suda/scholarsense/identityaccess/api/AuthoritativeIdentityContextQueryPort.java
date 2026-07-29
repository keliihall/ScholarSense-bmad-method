package cn.edu.suda.scholarsense.identityaccess.api;

import java.util.Optional;

/** Public read boundary; callers pass the stable actor pseudonym, never raw OIDC claims. */
@FunctionalInterface
public interface AuthoritativeIdentityContextQueryPort {
    Optional<AuthoritativeIdentityContext> findCurrent(String actorPseudonym);
}
