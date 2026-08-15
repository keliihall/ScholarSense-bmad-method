package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;

@FunctionalInterface
public interface RecoveryObservationTrustedTimePort {
    Instant now();
}
