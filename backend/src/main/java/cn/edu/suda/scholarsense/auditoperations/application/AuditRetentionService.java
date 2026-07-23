package cn.edu.suda.scholarsense.auditoperations.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Replay-safe, fenced executor for synthetic audit-domain retention evidence only. */
public final class AuditRetentionService {
    private final RetentionExecutionRepository executions;
    private final RetentionGuardPort guards;
    private final SyntheticFixtureDestroyPort fixtures;
    private final RetentionEligibilityEvaluator evaluator;

    public AuditRetentionService(
            RetentionExecutionRepository executions,
            RetentionGuardPort guards,
            SyntheticFixtureDestroyPort fixtures,
            RetentionEligibilityEvaluator evaluator) {
        this.executions = Objects.requireNonNull(executions);
        this.guards = Objects.requireNonNull(guards);
        this.fixtures = Objects.requireNonNull(fixtures);
        this.evaluator = Objects.requireNonNull(evaluator);
    }

    public RetentionExecution execute(RetentionExecutionCommand command) {
        validate(command);
        RetentionExecution existing = executions.find(command.executionId()).orElse(null);
        if (existing != null) {
            if (existing.sameIdempotencyIdentity(command)) return existing;
            throw new AuditRetentionException("AUDIT_RETENTION_IDEMPOTENCY_CONFLICT");
        }
        RetentionGuardSnapshot guard = guards.snapshot(command);
        RetentionExecution queued = create(command, guard, RetentionExecutionState.QUEUED, 1, List.of(),
                List.of(step("evaluate", "pending", null)));
        try {
            executions.create(queued);
        } catch (RetentionExecutionConflictException concurrent) {
            RetentionExecution winner = executions.find(command.executionId()).orElseThrow();
            if (winner.sameIdempotencyIdentity(command)) return winner;
            throw new AuditRetentionException("AUDIT_RETENTION_IDEMPOTENCY_CONFLICT");
        }

        List<String> unmet = unmetGuards(command, guard);
        if (!unmet.isEmpty()) {
            return replace(queued, create(command, guard, RetentionExecutionState.BLOCKED, 2, unmet,
                    blockedSteps(unmet.getFirst())));
        }

        RetentionEvaluation evaluation = evaluator.evaluate(
                guard.schedule(), guard.candidates(), guard.holds(), guard.trustedTime().value());
        RetentionExecution running = replace(queued, create(command, guard, RetentionExecutionState.RUNNING, 2,
                List.of(), runningSteps()));
        if (!guards.stillCurrent(guard, command.fencingToken())) {
            return replace(running, create(command, guard, RetentionExecutionState.BLOCKED, 3,
                    List.of("watermark-stable"), blockedSteps("AUDIT_RETENTION_FENCE_STALE")));
        }
        try {
            if (!evaluation.eligibleIds().isEmpty()) {
                fixtures.destroyOnce(command.executionId(), guard.guardVersion(),
                        command.fencingToken(), evaluation.eligibleIds(), guard);
            }
        } catch (RetentionExecutionConflictException stale) {
            return replace(running, create(command, guard, RetentionExecutionState.BLOCKED, 3,
                    List.of("watermark-stable"), blockedSteps("AUDIT_RETENTION_FENCE_STALE")));
        } catch (RuntimeException failure) {
            return replace(running, create(command, guard, RetentionExecutionState.FAILED, 3,
                    List.of(), failedDestroySteps("AUDIT_RETENTION_FIXTURE_DESTROY_FAILED")));
        }
        boolean held = !evaluation.heldIds().isEmpty();
        return replace(running, create(command, guard,
                held ? RetentionExecutionState.BLOCKED : RetentionExecutionState.SUCCEEDED,
                3, held ? List.of("legal-hold-clear") : List.of(), completedSteps(held)));
    }

    private RetentionExecution replace(RetentionExecution current, RetentionExecution replacement) {
        try {
            return executions.replace(current.executionId(), current.aggregateVersion(),
                    current.fencingToken(), replacement);
        } catch (RetentionExecutionConflictException stale) {
            throw new AuditRetentionException("AUDIT_RETENTION_FENCE_STALE");
        }
    }

    private static List<String> unmetGuards(
            RetentionExecutionCommand command, RetentionGuardSnapshot guard) {
        List<String> unmet = new ArrayList<>();
        if (!guard.schedule().approved()
                || !command.scheduleVersion().equals(guard.schedule().scheduleVersion())
                || !"RS-1.0.0".equals(command.scheduleVersion())) unmet.add("schedule-approved");
        if (!guard.ledgerHealthy()) unmet.add("ledger-healthy");
        if (!guard.rangeVerified()) unmet.add("range-verified");
        if (!guard.archiveReadBackVerified()) unmet.add("archive-readback-verified");
        if (!guard.trustedTime().fresh()) unmet.add("trusted-time-fresh");
        if (guard.sourceLedgerHead() < command.asOfSequence()
                || guard.projectionWatermark() < command.asOfSequence()) unmet.add("watermark-stable");
        return List.copyOf(unmet);
    }

    private static RetentionExecution create(
            RetentionExecutionCommand command,
            RetentionGuardSnapshot guard,
            RetentionExecutionState state,
            long aggregateVersion,
            List<String> unmet,
            List<RetentionExecutionStep> steps) {
        return new RetentionExecution(
                "AUDIT-RETENTION-EXECUTION-1.0.0", command.executionId(), command.scheduleVersion(),
                "audit-domain", command.fixtureId(), command.scopeHash(), command.asOfSequence(),
                command.requestDigest(), state, 1, command.fencingToken(), aggregateVersion,
                "destroy-fixture", guard.sourceLedgerHead(), guard.projectionWatermark(), guard.archiveDigest(),
                command.actor(), guard.trustedTime().value(), command.traceId(), true, unmet, steps);
    }

    private static void validate(RetentionExecutionCommand command) {
        if (command == null || command.executionId() == null || command.executionId().version() != 7
                || command.executionId().variant() != 2) {
            throw new AuditRetentionException("AUDIT_RETENTION_EXECUTION_ID_INVALID");
        }
        if (!"RS-1.0.0".equals(command.scheduleVersion())
                || command.fixtureId() == null || command.fixtureId().isBlank()
                || !hash(command.scopeHash()) || command.asOfSequence() < 0
                || !hash(command.requestDigest()) || command.actor() == null || command.actor().isBlank()
                || command.traceId() == null || command.traceId().isBlank() || command.fencingToken() < 1) {
            throw new AuditRetentionException("AUDIT_RETENTION_COMMAND_INVALID");
        }
    }

    private static boolean hash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static RetentionExecutionStep step(String name, String status, String code) {
        return new RetentionExecutionStep(name, status, code);
    }

    private static List<RetentionExecutionStep> runningSteps() {
        return List.of(step("evaluate", "succeeded", null), step("archive-write", "succeeded", null),
                step("archive-readback", "succeeded", null), step("destroy-fixture", "pending", null),
                step("persist-evidence", "pending", null));
    }

    private static List<RetentionExecutionStep> completedSteps(boolean held) {
        return List.of(step("evaluate", "succeeded", null), step("archive-write", "succeeded", null),
                step("archive-readback", "succeeded", null),
                step("destroy-fixture", held ? "blocked" : "succeeded",
                        held ? "AUDIT_RETENTION_BLOCKED_BY_LEGAL_HOLD" : null),
                step("persist-evidence", "succeeded", null));
    }

    private static List<RetentionExecutionStep> blockedSteps(String code) {
        return List.of(step("evaluate", "blocked", code), step("archive-write", "blocked", code),
                step("archive-readback", "blocked", code), step("destroy-fixture", "blocked", code),
                step("persist-evidence", "succeeded", null));
    }

    private static List<RetentionExecutionStep> failedDestroySteps(String code) {
        return List.of(step("evaluate", "succeeded", null), step("archive-write", "succeeded", null),
                step("archive-readback", "succeeded", null), step("destroy-fixture", "failed", code),
                step("persist-evidence", "succeeded", null));
    }
}
