package cn.edu.suda.scholarsense.identityaccess.application;

import java.time.Instant;

@FunctionalInterface
public interface AccessInvalidationOutboxRelayPort {
    int relay(int batchSize, Instant now);
}
