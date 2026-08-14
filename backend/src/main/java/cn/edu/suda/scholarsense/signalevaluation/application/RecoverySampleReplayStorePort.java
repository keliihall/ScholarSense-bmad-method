package cn.edu.suda.scholarsense.signalevaluation.application;

import java.util.Optional;

/**
 * Durable replay boundary. Implementations atomically return the existing winner when concurrent
 * writers race on {@code (providerVersion, requestDigest)}.
 */
public interface RecoverySampleReplayStorePort {
    Optional<RecoverySampleReplayEntry> find(String providerVersion, String requestDigest);

    RecoverySampleReplayEntry insertIfAbsent(RecoverySampleReplayEntry requested);
}
