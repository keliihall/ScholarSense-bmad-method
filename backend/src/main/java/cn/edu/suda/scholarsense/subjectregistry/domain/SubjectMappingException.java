package cn.edu.suda.scholarsense.subjectregistry.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SubjectMappingException(
        UUID exceptionId,
        IdentifierKey identifierKey,
        MappingExceptionCode exceptionCode,
        String sourceOwner,
        MappingExceptionStatus status,
        MappingResolutionCode resolutionCode,
        long aggregateVersion,
        Instant detectedAt,
        Instant updatedAt) {

    public SubjectMappingException {
        SubjectRegistryDomainRules.requireUuidV7(
                exceptionId, SubjectRegistryErrorCode.SUBJECT_REGISTRY_IDENTIFIER_INVALID);
        Objects.requireNonNull(identifierKey);
        Objects.requireNonNull(exceptionCode);
        SubjectRegistryDomainRules.requireBounded(sourceOwner, 1, 128);
        Objects.requireNonNull(status);
        Objects.requireNonNull(detectedAt);
        Objects.requireNonNull(updatedAt);
        if (aggregateVersion < 1 || aggregateVersion > SubjectRegistryDomainRules.MAX_SAFE_VERSION) {
            throw new SubjectRegistryException(
                    SubjectRegistryErrorCode.SUBJECT_REGISTRY_VERSION_CONFLICT);
        }
        boolean terminal = status == MappingExceptionStatus.RESOLVED
                || status == MappingExceptionStatus.DISMISSED;
        if (terminal != (resolutionCode != null)) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_EXCEPTION_SHAPE_INVALID");
        }
    }

    public static SubjectMappingException open(
            UUID exceptionId, IdentifierKey key, MappingExceptionCode code,
            String sourceOwner, Instant detectedAt) {
        return new SubjectMappingException(
                exceptionId, key, code, sourceOwner, MappingExceptionStatus.OPEN,
                null, 1, detectedAt, detectedAt);
    }

    public SubjectMappingException beginReview(Instant at) {
        if (status != MappingExceptionStatus.OPEN) throw invalidTransition();
        return transition(MappingExceptionStatus.IN_REVIEW, null, at);
    }

    public SubjectMappingException resolve(MappingResolutionCode code, Instant at) {
        if (status != MappingExceptionStatus.IN_REVIEW) throw invalidTransition();
        if (code == MappingResolutionCode.FALSE_POSITIVE_DISMISSED) throw invalidTransition();
        return transition(MappingExceptionStatus.RESOLVED, Objects.requireNonNull(code), at);
    }

    public SubjectMappingException dismiss(Instant at) {
        if (status != MappingExceptionStatus.OPEN && status != MappingExceptionStatus.IN_REVIEW) {
            throw invalidTransition();
        }
        return transition(
                MappingExceptionStatus.DISMISSED,
                MappingResolutionCode.FALSE_POSITIVE_DISMISSED,
                at);
    }

    private SubjectMappingException transition(
            MappingExceptionStatus nextStatus, MappingResolutionCode nextResolution, Instant at) {
        if (aggregateVersion == SubjectRegistryDomainRules.MAX_SAFE_VERSION) {
            throw new SubjectRegistryException(
                    SubjectRegistryErrorCode.SUBJECT_REGISTRY_VERSION_CONFLICT);
        }
        Objects.requireNonNull(at);
        if (at.isBefore(updatedAt)) throw invalidTransition();
        return new SubjectMappingException(
                exceptionId, identifierKey, exceptionCode, sourceOwner, nextStatus,
                nextResolution, aggregateVersion + 1, detectedAt, at);
    }

    private static SubjectRegistryException invalidTransition() {
        return new SubjectRegistryException(
                SubjectRegistryErrorCode.SUBJECT_REGISTRY_INVALID_TRANSITION);
    }
}
