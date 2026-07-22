package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;
import java.util.UUID;

@FunctionalInterface
public interface FindingIdPort {
    UUID newId(Instant instant);
}
