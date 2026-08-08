package cn.edu.suda.scholarsense.subjectregistry.domain;

import java.util.Objects;
import java.util.UUID;

public record SubjectMapping(
        UUID mappingId,
        UUID mappingAggregateId,
        IdentifierKey identifierKey,
        StudentRef studentRef,
        EffectiveInterval effectiveInterval,
        long mappingVersion) {

    public static final long MAX_VERSION = SubjectRegistryDomainRules.MAX_SAFE_VERSION;

    public SubjectMapping {
        SubjectRegistryDomainRules.requireUuidV7(
                mappingId, SubjectRegistryErrorCode.SUBJECT_REGISTRY_IDENTIFIER_INVALID);
        SubjectRegistryDomainRules.requireUuidV7(
                mappingAggregateId, SubjectRegistryErrorCode.SUBJECT_REGISTRY_IDENTIFIER_INVALID);
        Objects.requireNonNull(identifierKey);
        Objects.requireNonNull(studentRef);
        Objects.requireNonNull(effectiveInterval);
        if (mappingVersion < 1 || mappingVersion > MAX_VERSION) {
            throw new SubjectRegistryException(
                    SubjectRegistryErrorCode.SUBJECT_REGISTRY_VERSION_CONFLICT);
        }
    }

    public static SubjectMapping active(
            UUID mappingId,
            UUID mappingAggregateId,
            IdentifierKey identifierKey,
            StudentRef studentRef,
            EffectiveInterval effectiveInterval,
            long mappingVersion) {
        return new SubjectMapping(
                mappingId, mappingAggregateId, identifierKey, studentRef,
                effectiveInterval, mappingVersion);
    }

    public boolean activeAt(java.time.Instant instant) {
        return effectiveInterval.contains(instant);
    }
}
