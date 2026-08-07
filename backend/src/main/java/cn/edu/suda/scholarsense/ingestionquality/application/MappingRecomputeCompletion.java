package cn.edu.suda.scholarsense.ingestionquality.application;

public record MappingRecomputeCompletion(
        String resultCode,
        boolean historyCorrected,
        boolean businessPublicationCreated) {}
