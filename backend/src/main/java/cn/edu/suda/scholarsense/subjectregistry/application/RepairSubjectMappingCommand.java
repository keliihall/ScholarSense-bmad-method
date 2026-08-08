package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.subjectregistry.domain.CorrectionReason;
import cn.edu.suda.scholarsense.subjectregistry.domain.CorrectionType;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectLink;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMapping;
import java.util.Objects;
import java.util.UUID;

public record RepairSubjectMappingCommand(
        UUID exceptionId,
        long expectedVersion,
        String idempotencyKey,
        CorrectionReason reason,
        String sourceWatermark,
        CorrectionType correctionType,
        SubjectLink subjectLink,
        ActorContext actor,
        String traceId) {
    public RepairSubjectMappingCommand {
        Objects.requireNonNull(exceptionId);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(correctionType);
        Objects.requireNonNull(subjectLink);
        Objects.requireNonNull(actor);
        if (expectedVersion < 1 || expectedVersion > SubjectMapping.MAX_VERSION
                || idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}")
                || sourceWatermark == null || sourceWatermark.isBlank()
                || sourceWatermark.length() > 128
                || traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_REPAIR_COMMAND_INVALID");
        }
    }
}
