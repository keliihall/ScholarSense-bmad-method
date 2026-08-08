package cn.edu.suda.scholarsense.subjectregistry.application;

@FunctionalInterface
public interface SubjectRegistryAuditPort {
    void append(SubjectRegistryAuditEvent event);
}
