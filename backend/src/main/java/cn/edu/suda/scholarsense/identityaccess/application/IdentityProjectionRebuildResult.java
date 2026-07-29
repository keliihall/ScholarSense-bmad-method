package cn.edu.suda.scholarsense.identityaccess.application;

public record IdentityProjectionRebuildResult(
        int batchCount,
        int factCount,
        long sourceVersion,
        long sourceWatermark,
        long aggregateVersion) {}
