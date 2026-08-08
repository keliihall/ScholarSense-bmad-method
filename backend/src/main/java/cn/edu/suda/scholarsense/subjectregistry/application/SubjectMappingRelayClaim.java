package cn.edu.suda.scholarsense.subjectregistry.application;

import java.util.Objects;

public record SubjectMappingRelayClaim(SubjectMappingRelayEvent event, long attempts) {
    public SubjectMappingRelayClaim {
        Objects.requireNonNull(event);
        if (attempts < 1) throw new IllegalArgumentException("SUBJECT_MAPPING_RELAY_CLAIM_INVALID");
    }
}
