package cn.edu.suda.scholarsense.identityaccess.application;

public record IdentitySyncResult(
        IdentitySyncOutcome outcome,
        String reasonCode,
        long sourceWatermark,
        long sourceVersion,
        long aggregateVersion) {}
