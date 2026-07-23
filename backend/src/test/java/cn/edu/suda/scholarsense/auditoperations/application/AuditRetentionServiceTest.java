package cn.edu.suda.scholarsense.auditoperations.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditRetentionServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void threeYearAndHalfOpenHoldBoundariesAreExactAndTargeted() {
        RetentionEligibilityEvaluator evaluator = new RetentionEligibilityEvaluator();
        RetentionSchedule schedule = RetentionSchedule.rs100();
        List<RetentionCandidate> candidates = List.of(
                new RetentionCandidate("before", Instant.parse("2023-07-23T00:01:00.001Z"), "scope-a"),
                new RetentionCandidate("equal", Instant.parse("2023-07-23T00:00:00Z"), "scope-a"),
                new RetentionCandidate("after", Instant.parse("2023-07-22T23:59:59.999Z"), "scope-b"));
        AuditLegalHold hold = new AuditLegalHold(
                AuditUuidV7.generate(NOW), 1, "investigation", "scope-a", "legal-office",
                NOW, NOW.plusSeconds(60), NOW.plusSeconds(30));

        RetentionEvaluation atStart = evaluator.evaluate(schedule, candidates, List.of(hold), NOW);
        assertEquals(List.of("after"), atStart.eligibleIds());
        assertEquals(List.of("equal"), atStart.heldIds());
        assertEquals(List.of("before"), atStart.notDueIds());

        RetentionEvaluation atEnd = evaluator.evaluate(schedule, candidates, List.of(hold), NOW.plusSeconds(60));
        assertEquals(List.of("equal", "after"), atEnd.eligibleIds());
        assertTrue(atEnd.heldIds().isEmpty());
    }

    @Test
    void exactReplayReturnsSameEvidenceAndConflictNeverOverwrites() {
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        RecordingFixtureDestroyer destroyer = new RecordingFixtureDestroyer();
        AuditRetentionService service = service(executions, destroyer, stableGuard());
        RetentionExecutionCommand command = command();

        RetentionExecution first = service.execute(command);
        RetentionExecution replay = service.execute(command);

        assertEquals(RetentionExecutionState.SUCCEEDED, first.state());
        assertEquals(first, replay);
        assertEquals(1, destroyer.calls);
        RetentionExecutionCommand conflict = new RetentionExecutionCommand(
                command.executionId(), "RS-1.0.0", command.fixtureId(), "b".repeat(64),
                command.asOfSequence(), command.requestDigest(), command.actor(), command.traceId(), 1);
        assertEquals("AUDIT_RETENTION_IDEMPOTENCY_CONFLICT", assertThrows(
                AuditRetentionException.class, () -> service.execute(conflict)).code());
        assertEquals(first, executions.find(command.executionId()).orElseThrow());
    }

    @Test
    void aTargetedHoldDoesNotBlockUnheldFixtureButOverallEvidenceIsBlocked() {
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        RecordingFixtureDestroyer destroyer = new RecordingFixtureDestroyer();
        AuditLegalHold hold = new AuditLegalHold(
                AuditUuidV7.generate(NOW), 1, "case", "scope-held", "legal-office",
                NOW.minusSeconds(10), NOW.plusSeconds(10), NOW);
        RetentionGuardSnapshot guard = stableGuard(List.of(hold));

        RetentionExecution execution = service(executions, destroyer, guard).execute(command());

        assertEquals(RetentionExecutionState.BLOCKED, execution.state());
        assertEquals(List.of("fixture-free"), destroyer.destroyedIds);
        assertEquals(List.of("legal-hold-clear"), execution.unmetGuards());
        assertTrue(execution.nonProductionEvidence());
        assertEquals("audit-domain", execution.scopeType());
    }

    @Test
    void staleTrustedTimePolicyArchiveOrFenceFailsClosedBeforeDestroy() {
        for (RetentionGuardSnapshot guard : List.of(
                stableGuard().withTrustedTime(new TrustedAuditTime(NOW, false)),
                stableGuard().withSchedule(new RetentionSchedule("RS-1.0.0", 3, false)),
                stableGuard().withArchiveVerified(false),
                stableGuard().withLedgerHealthy(false))) {
            RecordingFixtureDestroyer destroyer = new RecordingFixtureDestroyer();
            RetentionExecution execution = service(new InMemoryExecutionRepository(), destroyer, guard).execute(command());
            assertEquals(RetentionExecutionState.BLOCKED, execution.state());
            assertEquals(0, destroyer.calls);
            assertFalse(execution.unmetGuards().isEmpty());
        }

        RecordingFixtureDestroyer destroyer = new RecordingFixtureDestroyer();
        RetentionGuardPort stale = new FixedGuardPort(stableGuard(), false);
        RetentionExecution execution = new AuditRetentionService(
                new InMemoryExecutionRepository(), stale, destroyer, new RetentionEligibilityEvaluator()).execute(command());
        assertEquals(RetentionExecutionState.BLOCKED, execution.state());
        assertEquals(List.of("watermark-stable"), execution.unmetGuards());
        assertEquals(0, destroyer.calls);
    }

    @Test
    void holdOrPolicyChangeInsideDestroyCriticalSectionBlocksBeforeMutation() {
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        RecordingFixtureDestroyer destroyer = new RecordingFixtureDestroyer();
        destroyer.currentGuardVersion = 8;

        RetentionExecution execution = new AuditRetentionService(
                executions, new FixedGuardPort(stableGuard(), true),
                destroyer, new RetentionEligibilityEvaluator())
                .execute(command());

        assertEquals(RetentionExecutionState.BLOCKED, execution.state());
        assertEquals(List.of("watermark-stable"), execution.unmetGuards());
        assertEquals(0, destroyer.calls);
        assertTrue(destroyer.atomicGuardChecks > 0);
    }

    @Test
    void onlyOpaqueUuidV7AndSyntheticAuditDomainScopeAreAccepted() {
        RetentionExecutionCommand invalid = new RetentionExecutionCommand(
                UUID.randomUUID(), "RS-1.0.0", "fixture", HASH, 42, HASH,
                "fixture-worker", "a".repeat(32), 1);
        assertEquals("AUDIT_RETENTION_EXECUTION_ID_INVALID", assertThrows(
                AuditRetentionException.class,
                () -> service(new InMemoryExecutionRepository(), new RecordingFixtureDestroyer(), stableGuard())
                        .execute(invalid)).code());
    }

    private static AuditRetentionService service(
            RetentionExecutionRepository executions,
            SyntheticFixtureDestroyPort destroyer,
            RetentionGuardSnapshot guard) {
        return new AuditRetentionService(
                executions, new FixedGuardPort(guard, true), destroyer, new RetentionEligibilityEvaluator());
    }

    private static RetentionExecutionCommand command() {
        return new RetentionExecutionCommand(
                AuditUuidV7.generate(NOW), "RS-1.0.0", "fixture-a", HASH, 42, HASH,
                "fixture-worker", "a".repeat(32), 1);
    }

    private static RetentionGuardSnapshot stableGuard() {
        return stableGuard(List.of());
    }

    private static RetentionGuardSnapshot stableGuard(List<AuditLegalHold> holds) {
        return new RetentionGuardSnapshot(
                RetentionSchedule.rs100(), new TrustedAuditTime(NOW, true), true, true, true,
                42, 42, "4".repeat(64), 7, holds,
                List.of(
                        new RetentionCandidate("fixture-free", Instant.parse("2023-07-22T00:00:00Z"), "scope-free"),
                        new RetentionCandidate("fixture-held", Instant.parse("2023-07-22T00:00:00Z"), "scope-held")));
    }

    private record FixedGuardPort(RetentionGuardSnapshot snapshot, boolean current) implements RetentionGuardPort {
        @Override
        public RetentionGuardSnapshot snapshot(RetentionExecutionCommand command) {
            return snapshot;
        }

        @Override
        public boolean stillCurrent(RetentionGuardSnapshot expected, long fencingToken) {
            return current;
        }
    }

    private static final class RecordingFixtureDestroyer implements SyntheticFixtureDestroyPort {
        private int calls;
        private int atomicGuardChecks;
        private long currentGuardVersion = 7;
        private List<String> destroyedIds = List.of();

        @Override
        public void destroyOnce(
                UUID executionId,
                long expectedGuardVersion,
                long fencingToken,
                List<String> fixtureRecordIds,
                RetentionGuardSnapshot expectedGuard) {
            assertEquals(7, expectedGuardVersion);
            assertEquals(1, fencingToken);
            assertEquals(expectedGuardVersion, expectedGuard.guardVersion());
            atomicGuardChecks++;
            if (currentGuardVersion != expectedGuardVersion) {
                throw new RetentionExecutionConflictException();
            }
            calls++;
            destroyedIds = List.copyOf(fixtureRecordIds);
        }
    }

    private static final class InMemoryExecutionRepository implements RetentionExecutionRepository {
        private final Map<UUID, RetentionExecution> values = new LinkedHashMap<>();

        @Override
        public Optional<RetentionExecution> find(UUID executionId) {
            return Optional.ofNullable(values.get(executionId));
        }

        @Override
        public RetentionExecution create(RetentionExecution execution) {
            if (values.putIfAbsent(execution.executionId(), execution) != null) {
                throw new RetentionExecutionConflictException();
            }
            return execution;
        }

        @Override
        public RetentionExecution replace(
                UUID executionId, long expectedAggregateVersion, long fencingToken, RetentionExecution replacement) {
            RetentionExecution current = values.get(executionId);
            if (current == null || current.aggregateVersion() != expectedAggregateVersion
                    || current.fencingToken() != fencingToken) {
                throw new RetentionExecutionConflictException();
            }
            values.put(executionId, replacement);
            return replacement;
        }
    }
}
