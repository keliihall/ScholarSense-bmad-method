package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Optional;

public interface ContinuationRepository {
    void save(StoredContinuation value);

    Optional<StoredContinuation> findByDigest(String digest);

    void markConsumed(String digest, Instant consumedAt);
}
