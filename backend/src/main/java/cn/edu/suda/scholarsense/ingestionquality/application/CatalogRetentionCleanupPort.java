package cn.edu.suda.scholarsense.ingestionquality.application;

/** Owner-local RS-1.0.0 cleanup capability; returns the number of root facts removed. */
@FunctionalInterface
public interface CatalogRetentionCleanupPort {
    long cleanupExpired();
}
