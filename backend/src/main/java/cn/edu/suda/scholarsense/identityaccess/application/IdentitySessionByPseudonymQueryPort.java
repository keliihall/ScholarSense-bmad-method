package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import java.util.Optional;

/** Current session lookup used by server-owned cross-module authorization bridges. */
@FunctionalInterface
public interface IdentitySessionByPseudonymQueryPort {
    Optional<IdentitySession> findCurrent(String sessionPseudonym);
}
