package cn.edu.suda.scholarsense.contractfixture;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test-only command harness. It is intentionally absent from main source and public APIs. */
final class FixtureCommandHarness {

    private static final Instant FIXED_CHANGE_TIME = Instant.parse("2026-07-18T00:00:00Z");

    private final FixtureAggregate aggregate = new FixtureAggregate();
    private final AuthorizationFake authorization;
    private final DependencyFake dependency;
    private final AuditFake audit;
    private final Map<String, IdempotencyEntry> idempotency = new LinkedHashMap<>();
    private final List<String> committedSideEffects = new ArrayList<>();

    FixtureCommandHarness() {
        this(FailureMode.NONE);
    }

    FixtureCommandHarness(FailureMode failureMode) {
        authorization = new AuthorizationFake(failureMode != FailureMode.AUTHORIZATION);
        dependency = new DependencyFake(failureMode != FailureMode.DEPENDENCY);
        audit = new AuditFake(failureMode != FailureMode.AUDIT);
    }

    synchronized CommandResult execute(Command command) {
        IdempotencyEntry previous = idempotency.get(command.idempotencyKey());
        if (previous != null) {
            if (previous.requestHash().equals(command.requestHash())) {
                return previous.result();
            }
            return failure(
                    409,
                    "FIXTURE_IDEMPOTENCY_MISMATCH",
                    "Idempotency key was already used for another request",
                    command.traceId(),
                    null,
                    null,
                    null);
        }

        try {
            authorization.check();
            dependency.check();
        } catch (FixtureFailure failure) {
            return failure(
                    failure.status,
                    failure.code,
                    failure.safeMessage,
                    command.traceId(),
                    null,
                    null,
                    null);
        }

        if (command.expectedVersion() != aggregate.version) {
            return failure(
                    409,
                    "FIXTURE_VERSION_CONFLICT",
                    "Aggregate version is stale",
                    command.traceId(),
                    aggregate.version,
                    aggregate.latestOperator,
                    aggregate.latestChangedAt);
        }

        final String preparedAudit;
        try {
            preparedAudit = audit.prepare(command.operator(), "advanced");
        } catch (FixtureFailure failure) {
            return failure(
                    failure.status,
                    failure.code,
                    failure.safeMessage,
                    command.traceId(),
                    null,
                    null,
                    null);
        }

        aggregate.advance(command.operator(), FIXED_CHANGE_TIME);
        committedSideEffects.add(preparedAudit);
        CommandResult result = new CommandResult(200, aggregate.state, null);
        idempotency.put(command.idempotencyKey(), new IdempotencyEntry(command.requestHash(), result));
        return result;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(aggregate.state, aggregate.version, aggregate.latestOperator, aggregate.latestChangedAt);
    }

    synchronized List<String> committedSideEffects() {
        return List.copyOf(committedSideEffects);
    }

    private CommandResult failure(
            int status,
            String code,
            String safeMessage,
            String traceId,
            Integer currentVersion,
            String latestOperator,
            Instant latestChangedAt) {
        return new CommandResult(
                status,
                null,
                new ErrorEnvelope(
                        code,
                        safeMessage,
                        traceId,
                        List.of(),
                        currentVersion,
                        latestOperator,
                        latestChangedAt));
    }

    enum FailureMode {
        NONE,
        AUTHORIZATION,
        DEPENDENCY,
        AUDIT
    }

    record Command(
            String idempotencyKey,
            String requestHash,
            int expectedVersion,
            String traceId,
            String operator,
            String sensitiveInput) {}

    record CommandResult(int status, String value, ErrorEnvelope error) {}

    record ErrorEnvelope(
            String code,
            String message,
            String traceId,
            List<FieldError> fieldErrors,
            Integer currentVersion,
            String latestOperator,
            Instant latestChangedAt) {}

    record FieldError(String field, String code, String message) {}

    record Snapshot(String state, int version, String latestOperator, Instant latestChangedAt) {}

    private record IdempotencyEntry(String requestHash, CommandResult result) {}

    private static final class FixtureAggregate {
        private String state = "initial";
        private int version;
        private String latestOperator;
        private Instant latestChangedAt;

        private void advance(String operator, Instant changedAt) {
            state = "advanced";
            version++;
            latestOperator = operator;
            latestChangedAt = changedAt;
        }
    }

    private static final class AuthorizationFake {
        private final boolean allowed;

        private AuthorizationFake(boolean allowed) {
            this.allowed = allowed;
        }

        private void check() {
            if (!allowed) {
                throw new FixtureFailure(403, "FIXTURE_AUTHORIZATION_DENIED", "Operation is not authorized");
            }
        }
    }

    private static final class DependencyFake {
        private final boolean available;

        private DependencyFake(boolean available) {
            this.available = available;
        }

        private void check() {
            if (!available) {
                throw new FixtureFailure(503, "FIXTURE_DEPENDENCY_UNAVAILABLE", "Required dependency is unavailable");
            }
        }
    }

    private static final class AuditFake {
        private final boolean available;

        private AuditFake(boolean available) {
            this.available = available;
        }

        private String prepare(String operator, String state) {
            if (!available) {
                throw new FixtureFailure(503, "FIXTURE_AUDIT_FAILURE", "Audit commit could not be prepared");
            }
            return "audit:" + operator + ":" + state;
        }
    }

    private static final class FixtureFailure extends RuntimeException {
        private final int status;
        private final String code;
        private final String safeMessage;

        private FixtureFailure(int status, String code, String safeMessage) {
            super(code);
            this.status = status;
            this.code = code;
            this.safeMessage = safeMessage;
        }
    }
}
