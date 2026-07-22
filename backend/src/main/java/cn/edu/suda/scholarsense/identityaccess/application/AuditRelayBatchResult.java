package cn.edu.suda.scholarsense.identityaccess.application;

public record AuditRelayBatchResult(
        int claimed,
        int confirmed,
        int retried,
        int failed,
        int fenced) {}
