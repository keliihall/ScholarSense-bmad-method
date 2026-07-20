package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;
import java.util.Optional;

public interface HostBootstrapRepository {
    void saveBootstrap(StoredHostBootstrap bootstrap);

    Optional<StoredHostBootstrap> findBootstrapByDigest(String codeDigest);

    boolean consumeOnce(String codeDigest, Instant consumedAt);
}
