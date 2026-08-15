package cn.edu.suda.scholarsense.ingestionquality.application;

import cn.edu.suda.scholarsense.shared.observability.ObservationPort;
import cn.edu.suda.scholarsense.shared.observability.SafeObservationAttributes;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContext;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContextCodec;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Executes durable recompute jobs; every mutation is guarded by the claimed fencing token. */
public final class SubjectWindowRecomputeProcessor {
    private static final int BATCH_SIZE = 100;
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final String WORKER_ID = "subject-recompute-worker";
    private final SubjectWindowRecomputeWorkPort work;
    private final MappingRecomputeIdPort ids;
    private final Clock clock;
    private final ObservationPort observations;
    private final W3cTraceContextCodec traceCodec;

    public SubjectWindowRecomputeProcessor(
            SubjectWindowRecomputeWorkPort work,
            MappingRecomputeIdPort ids,
            Clock clock) {
        this(work, ids, clock, null, null);
    }

    public SubjectWindowRecomputeProcessor(
            SubjectWindowRecomputeWorkPort work,
            MappingRecomputeIdPort ids,
            Clock clock,
            ObservationPort observations,
            W3cTraceContextCodec traceCodec) {
        this.work = Objects.requireNonNull(work);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.observations = observations;
        this.traceCodec = traceCodec;
        if ((observations == null) != (traceCodec == null)) {
            throw new IllegalArgumentException("job observability must be configured atomically");
        }
    }

    public SubjectWindowRecomputeWorkerResult runBatch() {
        List<SubjectWindowRecomputeCandidate> jobs = work.findClaimable(
                BATCH_SIZE, clock.instant());
        int claimed = 0, succeeded = 0, failed = 0, fenced = 0;
        for (SubjectWindowRecomputeCandidate candidate : jobs) {
            W3cTraceContext durableParent = observations == null ? null
                    : durableParent(candidate);
            try (ObservationPort.ObservationScope attempt = start(
                    "job.attempt", durableParent)) {
            UUID jobId = candidate.jobId();
            long fence;
            try {
                fence = work.claim(jobId, WORKER_ID, clock.instant(), LEASE);
                claimed++;
            } catch (RuntimeException lostRace) {
                outcome(attempt, "conflict");
                fenced++;
                continue;
            }
            try {
                try (ObservationPort.ObservationScope evaluation = start(
                        "batch.evaluate", context(attempt, durableParent))) {
                    try {
                        work.checkpoint(
                                jobId, fence, candidate.checkpointSequence() + 1, clock.instant());
                    } catch (RuntimeException checkpointFailure) {
                        fail(evaluation, checkpointFailure);
                        throw checkpointFailure;
                    }
                }
                try (ObservationPort.ObservationScope finalization = start(
                        "job.finalize", context(attempt, durableParent));
                     ObservationPort.ObservationScope publication = start(
                             "outbox.publish", context(finalization,
                                     context(attempt, durableParent)))) {
                    try {
                        work.complete(jobId, fence, clock.instant(), ids.nextId());
                    } catch (RuntimeException completionFailure) {
                        fail(publication, completionFailure);
                        fail(finalization, completionFailure);
                        throw completionFailure;
                    }
                }
                outcome(attempt, "success");
                succeeded++;
            } catch (RuntimeException executionFailure) {
                error(attempt, executionFailure);
                try {
                    if (work.fail(jobId, fence, clock.instant(),
                            "INGESTION_QUALITY_RECOMPUTE_EXECUTION_FAILED")) {
                        outcome(attempt, "failure");
                        failed++;
                    } else {
                        outcome(attempt, "conflict");
                        fenced++;
                    }
                } catch (RuntimeException staleFence) {
                    outcome(attempt, "conflict");
                    fenced++;
                }
            }
            }
        }
        return new SubjectWindowRecomputeWorkerResult(claimed, succeeded, failed, fenced);
    }

    private W3cTraceContext durableParent(SubjectWindowRecomputeCandidate candidate) {
        if (candidate.traceparent() == null) {
            return traceCodec.newRoot(false);
        }
        return traceCodec.extract(candidate.traceparent(), true).context();
    }

    private ObservationPort.ObservationScope start(
            String operation, W3cTraceContext parent) {
        if (observations == null) return null;
        return observations.start(
                operation,
                "outbox.publish".equals(operation)
                        ? ObservationPort.ObservationKind.PRODUCER
                        : ObservationPort.ObservationKind.INTERNAL,
                SafeObservationAttributes.create()
                        .low("module", "ingestion-quality")
                        .low("operation", operation)
                        .low("outcome", "success"),
                parent);
    }

    private static W3cTraceContext context(
            ObservationPort.ObservationScope scope, W3cTraceContext fallback) {
        return scope == null ? fallback : scope.context();
    }

    private static void error(
            ObservationPort.ObservationScope scope, RuntimeException failure) {
        if (scope != null) scope.error(failure);
    }

    private static void fail(
            ObservationPort.ObservationScope scope, RuntimeException failure) {
        outcome(scope, "failure");
        error(scope, failure);
    }

    private static void outcome(
            ObservationPort.ObservationScope scope, String value) {
        if (scope != null) scope.outcome(value);
    }
}
