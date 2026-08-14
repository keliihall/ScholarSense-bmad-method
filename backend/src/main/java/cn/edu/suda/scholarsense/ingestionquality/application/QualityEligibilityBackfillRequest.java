package cn.edu.suda.scholarsense.ingestionquality.application;

public record QualityEligibilityBackfillRequest(
        String sourceId,
        long expectedSourceVersion,
        long actualSourceVersion,
        long cursorAggregateVersion) {}
