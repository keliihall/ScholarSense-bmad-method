package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.Optional;

public interface SessionIdempotencyRepository {
    Optional<StoredSessionCommand> find(String sessionId, SessionCommandType type, String key);

    /** Looks up an already-completed command after its browser session has been invalidated. */
    Optional<StoredSessionCommand> find(SessionCommandType type, String key);

    void save(StoredSessionCommand value);
}
