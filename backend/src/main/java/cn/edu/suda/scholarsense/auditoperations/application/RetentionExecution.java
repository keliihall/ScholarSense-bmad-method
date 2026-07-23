package cn.edu.suda.scholarsense.auditoperations.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RetentionExecution(
        String schemaVersion,
        UUID executionId,
        String scheduleVersion,
        String scopeType,
        String fixtureId,
        String scopeHash,
        long asOfSequence,
        String requestDigest,
        RetentionExecutionState state,
        long attemptNo,
        long fencingToken,
        long aggregateVersion,
        String action,
        long sourceLedgerHead,
        long projectionWatermark,
        String archiveDigest,
        String actor,
        Instant trustedAt,
        String traceId,
        boolean nonProductionEvidence,
        List<String> unmetGuards,
        List<RetentionExecutionStep> steps) {
    public RetentionExecution {
        unmetGuards = List.copyOf(unmetGuards);
        steps = List.copyOf(steps);
    }

    public boolean sameIdempotencyIdentity(RetentionExecutionCommand command) {
        return executionId.equals(command.executionId())
                && scheduleVersion.equals(command.scheduleVersion())
                && scopeHash.equals(command.scopeHash())
                && asOfSequence == command.asOfSequence()
                && requestDigest.equals(command.requestDigest());
    }

    public RetentionExecution withSteps(List<RetentionExecutionStep> replacement) {
        return new RetentionExecution(schemaVersion, executionId, scheduleVersion, scopeType, fixtureId,
                scopeHash, asOfSequence, requestDigest, state, attemptNo, fencingToken, aggregateVersion,
                action, sourceLedgerHead, projectionWatermark, archiveDigest, actor, trustedAt, traceId,
                nonProductionEvidence, unmetGuards, replacement);
    }
}
