package cn.edu.suda.scholarsense.identityaccess.application;

@FunctionalInterface
public interface IdentitySyncObservabilityPort {
    void record(IdentitySyncObservation observation);
}
