package cn.edu.suda.scholarsense.ingestionquality.application;

/** Generates owner-local UUIDv7 identities; the external authority cannot select them. */
@FunctionalInterface
public interface QualitySnapshotRetentionIdPort {
    QualitySnapshotRetentionAttemptIds nextAttempt();
}
