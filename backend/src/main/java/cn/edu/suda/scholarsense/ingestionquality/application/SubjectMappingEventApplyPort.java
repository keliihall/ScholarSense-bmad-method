package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;

@FunctionalInterface
public interface SubjectMappingEventApplyPort {
    SubjectMappingEventOutcome accept(
            SubjectMappingChangedFact fact, boolean backfill, Instant receivedAt);
}
