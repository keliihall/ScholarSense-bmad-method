package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.UUID;

@FunctionalInterface
public interface QualitySnapshotIdPort {
    UUID nextId(Instant evaluatedAt);
}
