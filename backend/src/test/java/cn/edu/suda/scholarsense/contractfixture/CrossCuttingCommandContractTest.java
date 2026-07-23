package cn.edu.suda.scholarsense.contractfixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.contractfixture.FixtureCommandHarness.CommandResult;
import cn.edu.suda.scholarsense.contractfixture.FixtureCommandHarness.FailureMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CrossCuttingCommandContractTest {

    @Test
    void fixtureRemainsOutsideProductionSourceWhileOwnedStoryMigrationExists() throws Exception {
        assertFalse(Files.exists(Path.of(
                "src/main/java/cn/edu/suda/scholarsense/contractfixture")));
        try (var walk = Files.walk(Path.of("src/main/resources/db/migration"))) {
            assertEquals(5, walk.filter(path -> path.toString().endsWith(".sql")).count());
        }
    }

    @Test
    void happyPathCommitsStateAndOneAuditedSideEffect() {
        FixtureCommandHarness harness = new FixtureCommandHarness();

        CommandResult result = harness.execute(command("happy-key", "hash-a", 0, "operator-a"));

        assertEquals(200, result.status());
        assertEquals("advanced", result.value());
        assertEquals(1, harness.snapshot().version());
        assertEquals("advanced", harness.snapshot().state());
        assertEquals(List.of("audit:operator-a:advanced"), harness.committedSideEffects());
    }

    @ParameterizedTest
    @MethodSource("failureCases")
    void authorizationDependencyAndAuditFailuresLeaveStateAndSideEffectsUnchanged(
            FailureMode failureMode, int expectedStatus, String expectedCode) {
        FixtureCommandHarness harness = new FixtureCommandHarness(failureMode);
        var before = harness.snapshot();

        CommandResult result = harness.execute(command("failure-key", "hash-sensitive", 0, "operator-a"));

        assertEquals(expectedStatus, result.status());
        assertEquals(before, harness.snapshot());
        assertTrue(harness.committedSideEffects().isEmpty());
        assertNotNull(result.error());
        assertEquals(expectedCode, result.error().code());
        assertEquals("trace-contract-001", result.error().traceId());
        assertFalse(result.error().toString().contains("sensitive-value"));
    }

    @Test
    void sameIdempotencyKeyAndHashReplaysOriginalWithoutDuplicateSideEffect() {
        FixtureCommandHarness harness = new FixtureCommandHarness();
        var command = command("replay-key", "same-hash", 0, "operator-a");

        CommandResult first = harness.execute(command);
        CommandResult replay = harness.execute(command);

        assertEquals(first, replay);
        assertEquals(1, harness.snapshot().version());
        assertEquals(1, harness.committedSideEffects().size());
    }

    @Test
    void reusedIdempotencyKeyWithDifferentHashReturnsStable409WithoutMutation() {
        FixtureCommandHarness harness = new FixtureCommandHarness();
        harness.execute(command("mismatch-key", "hash-a", 0, "operator-a"));
        var before = harness.snapshot();
        int effectsBefore = harness.committedSideEffects().size();

        CommandResult mismatch = harness.execute(command("mismatch-key", "hash-b", 1, "operator-b"));

        assertEquals(409, mismatch.status());
        assertEquals("FIXTURE_IDEMPOTENCY_MISMATCH", mismatch.error().code());
        assertEquals(before, harness.snapshot());
        assertEquals(effectsBefore, harness.committedSideEffects().size());
    }

    @Test
    void staleAggregateVersionReturnsCurrentVersionOperatorAndTimeWithoutMutation() {
        FixtureCommandHarness harness = new FixtureCommandHarness();
        harness.execute(command("first-key", "hash-a", 0, "operator-a"));
        var before = harness.snapshot();

        CommandResult conflict = harness.execute(command("stale-key", "hash-b", 0, "operator-b"));

        assertEquals(409, conflict.status());
        assertEquals("FIXTURE_VERSION_CONFLICT", conflict.error().code());
        assertEquals(1, conflict.error().currentVersion());
        assertEquals("operator-a", conflict.error().latestOperator());
        assertNotNull(conflict.error().latestChangedAt());
        assertEquals(before, harness.snapshot());
        assertEquals(1, harness.committedSideEffects().size());
    }

    @Test
    void concurrentWritersWithSameVersionAllowAtMostOneStateAdvance() throws Exception {
        FixtureCommandHarness harness = new FixtureCommandHarness();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> executeTogether(
                    harness, command("concurrent-a", "hash-a", 0, "operator-a"), ready, start));
            var second = executor.submit(() -> executeTogether(
                    harness, command("concurrent-b", "hash-b", 0, "operator-b"), ready, start));
            ready.await();
            start.countDown();
            List<CommandResult> results = List.of(first.get(), second.get());

            assertEquals(1, results.stream().filter(result -> result.status() == 200).count());
            assertEquals(1, results.stream().filter(result -> result.status() == 409).count());
        }
        assertEquals(1, harness.snapshot().version());
        assertEquals(1, harness.committedSideEffects().size());
    }

    private CommandResult executeTogether(
            FixtureCommandHarness harness,
            FixtureCommandHarness.Command command,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return harness.execute(command);
    }

    private FixtureCommandHarness.Command command(
            String key, String hash, int expectedVersion, String operator) {
        return new FixtureCommandHarness.Command(
                key, hash, expectedVersion, "trace-contract-001", operator, "sensitive-value");
    }

    private static Stream<Arguments> failureCases() {
        return Stream.of(
                Arguments.of(FailureMode.AUTHORIZATION, 403, "FIXTURE_AUTHORIZATION_DENIED"),
                Arguments.of(FailureMode.DEPENDENCY, 503, "FIXTURE_DEPENDENCY_UNAVAILABLE"),
                Arguments.of(FailureMode.AUDIT, 503, "FIXTURE_AUDIT_FAILURE"));
    }
}
