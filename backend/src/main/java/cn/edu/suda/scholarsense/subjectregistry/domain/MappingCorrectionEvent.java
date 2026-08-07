package cn.edu.suda.scholarsense.subjectregistry.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MappingCorrectionEvent(
        UUID eventId,
        UUID lineageId,
        UUID supersedesId,
        long aggregateVersion,
        CorrectionType correctionType,
        SubjectLink subjectLink,
        CorrectionReason reason,
        Instant effectiveAt) {

    public MappingCorrectionEvent {
        SubjectRegistryDomainRules.requireUuidV7(
                eventId, SubjectRegistryErrorCode.SUBJECT_REGISTRY_LINEAGE_INVALID);
        SubjectRegistryDomainRules.requireUuidV7(
                lineageId, SubjectRegistryErrorCode.SUBJECT_REGISTRY_LINEAGE_INVALID);
        if (supersedesId != null) {
            SubjectRegistryDomainRules.requireUuidV7(
                    supersedesId, SubjectRegistryErrorCode.SUBJECT_REGISTRY_LINEAGE_INVALID);
        }
        if (aggregateVersion < 1 || aggregateVersion > SubjectRegistryDomainRules.MAX_SAFE_VERSION) {
            throw new SubjectRegistryException(
                    SubjectRegistryErrorCode.SUBJECT_REGISTRY_VERSION_CONFLICT);
        }
        Objects.requireNonNull(correctionType);
        Objects.requireNonNull(subjectLink);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(effectiveAt);
        if ((aggregateVersion == 1) != (supersedesId == null)) {
            throw new SubjectRegistryException(
                    SubjectRegistryErrorCode.SUBJECT_REGISTRY_LINEAGE_INVALID);
        }
    }

    public static MappingCorrectionEvent first(
            UUID eventId, UUID lineageId, CorrectionType type, SubjectLink link,
            CorrectionReason reason, Instant effectiveAt) {
        return new MappingCorrectionEvent(
                eventId, lineageId, null, 1, type, link, reason, effectiveAt);
    }

    public static MappingCorrectionEvent next(
            UUID eventId, UUID lineageId, UUID supersedesId, long aggregateVersion,
            CorrectionType type, SubjectLink link, CorrectionReason reason, Instant effectiveAt) {
        return new MappingCorrectionEvent(
                eventId, lineageId, supersedesId, aggregateVersion,
                type, link, reason, effectiveAt);
    }
}
