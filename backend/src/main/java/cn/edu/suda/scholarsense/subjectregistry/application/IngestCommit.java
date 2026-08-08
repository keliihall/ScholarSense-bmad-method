package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMapping;
import java.util.Objects;
import java.util.Optional;

public record IngestCommit(
        Optional<SubjectMapping> mapping,
        Optional<SubjectMappingExceptionRecord> exceptionRecord,
        ProtectedIdentifierMaterial sourceIdentifier,
        String sourceWatermark,
        SubjectRegistryAuditEvent auditEvent) {
    public IngestCommit {
        mapping = Objects.requireNonNull(mapping);
        exceptionRecord = Objects.requireNonNull(exceptionRecord);
        Objects.requireNonNull(sourceIdentifier);
        Objects.requireNonNull(auditEvent);
        if (mapping.isPresent() == exceptionRecord.isPresent()) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_INGEST_COMMIT_SHAPE_INVALID");
        }
        if (sourceWatermark == null || sourceWatermark.isBlank()) {
            throw new IllegalArgumentException("SUBJECT_REGISTRY_WATERMARK_INVALID");
        }
    }

    @Override
    public String toString() {
        return "IngestCommit[mapping=" + mapping.map(item -> item.mappingId().toString())
                + ", exception=" + exceptionRecord.map(item -> item.exception().exceptionId().toString())
                + ", protected=REDACTED]";
    }
}
