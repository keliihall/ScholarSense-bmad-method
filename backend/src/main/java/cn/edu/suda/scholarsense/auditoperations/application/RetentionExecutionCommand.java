package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.UUID;

public record RetentionExecutionCommand(
        UUID executionId,
        String scheduleVersion,
        String fixtureId,
        String scopeHash,
        long asOfSequence,
        String requestDigest,
        String actor,
        String traceId,
        long fencingToken) {}
