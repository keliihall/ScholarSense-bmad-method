package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;

/** Supplies trusted, microsecond-precision server time for lease and retry decisions. */
@FunctionalInterface
public interface RecoveryValidationTrustedTimePort {
    Instant now();
}
