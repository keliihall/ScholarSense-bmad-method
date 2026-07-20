package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.IdentitySession;
import java.util.Optional;

public interface IdentitySessionRepository {
    Optional<IdentitySession> findById(String sessionId);

    /** Must acquire a database row lock in production before any remote refresh rotation. */
    default Optional<IdentitySession> findByIdForUpdate(String sessionId) {
        return findById(sessionId);
    }

    void save(IdentitySession session);
}
