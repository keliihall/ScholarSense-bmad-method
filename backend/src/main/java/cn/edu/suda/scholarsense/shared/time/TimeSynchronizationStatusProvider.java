package cn.edu.suda.scholarsense.shared.time;

import java.util.Optional;

/** Deployment-owned bridge to signed clock synchronization evidence. */
@FunctionalInterface
public interface TimeSynchronizationStatusProvider {
    Optional<TimeSynchronizationStatus> current();
}
