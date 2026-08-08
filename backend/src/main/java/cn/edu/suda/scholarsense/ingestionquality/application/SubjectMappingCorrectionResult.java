package cn.edu.suda.scholarsense.ingestionquality.application;

public record SubjectMappingCorrectionResult(
        SubjectMappingEventOutcome outcome, MappingRecomputePlan recomputePlan) {}
