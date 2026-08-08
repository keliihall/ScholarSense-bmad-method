package cn.edu.suda.scholarsense.subjectregistry.application;

@FunctionalInterface
public interface SubjectMappingAuthorizationProbePort {
    SubjectMappingExceptionRecord probe();
}
