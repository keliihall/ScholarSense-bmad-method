package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.subjectregistry.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierType;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMapping;
import java.util.Objects;

public record IngestSubjectIdentifierCommand(
        String sourceId,
        String sourceContractVersion,
        IdentifierType identifierType,
        String sourceNativeIdentifier,
        String authoritativeStudentNumber,
        EffectiveInterval effectiveInterval,
        long sourceVersion,
        String sourceWatermark,
        ActorContext actor,
        String traceId) {
    public IngestSubjectIdentifierCommand {
        Objects.requireNonNull(identifierType);
        Objects.requireNonNull(effectiveInterval);
        Objects.requireNonNull(actor);
        if (sourceId == null || sourceContractVersion == null
                || sourceNativeIdentifier == null || authoritativeStudentNumber == null
                || sourceVersion < 1 || sourceVersion > SubjectMapping.MAX_VERSION
                || sourceWatermark == null || sourceWatermark.isBlank()
                || sourceWatermark.length() > 128
                || traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_INGEST_COMMAND_INVALID");
        }
    }

    @Override
    public String toString() {
        return "IngestSubjectIdentifierCommand[REDACTED, sourceId=" + sourceId
                + ", sourceVersion=" + sourceVersion + "]";
    }
}
