package cn.edu.suda.scholarsense.subjectregistry.application;

public record SubjectMappingRelayResult(
        int claimed, int delivered, int retried, int failed, int fenced) {}
