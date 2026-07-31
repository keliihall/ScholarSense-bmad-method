package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityReconciliationJobPort;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityReconciliationResult;
import cn.edu.suda.scholarsense.identityaccess.application.RunningResponsibilityReconciliationAttempt;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResponsibilityReconciliationSchedulerTest {
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "responsibility-authority",
            "sandbox-0",
            "responsibility");

    @Test
    void sixOClockBoundaryUsesAsiaShanghaiBusinessDate() {
        var jobs = new FakeJobs();
        var scheduler = scheduler(
                jobs, LocalDate.of(2026, 7, 29));

        scheduler.catchUp(
                Instant.parse("2026-07-29T21:59:59Z"));
        assertEquals(
                List.of(LocalDate.of(2026, 7, 29)),
                jobs.enqueued);

        jobs.enqueued.clear();
        jobs.missed.clear();
        scheduler.catchUp(
                Instant.parse("2026-07-29T22:00:00Z"));
        assertEquals(
                List.of(
                        LocalDate.of(2026, 7, 29),
                        LocalDate.of(2026, 7, 30)),
                jobs.enqueued);
        assertEquals(List.of(), jobs.missed);
    }

    @Test
    void restartWithinCatchUpWindowEnqueuesOnlyOnce() {
        var jobs = new FakeJobs();
        var scheduler = scheduler(
                jobs, LocalDate.of(2026, 7, 28));
        Instant restarted =
                Instant.parse("2026-07-29T10:00:00Z");

        scheduler.catchUp(restarted);
        jobs.terminal.addAll(jobs.enqueued);
        scheduler.catchUp(restarted.plusSeconds(60));

        assertEquals(
                List.of(LocalDate.of(2026, 7, 29)),
                jobs.enqueued);
        assertEquals(
                List.of(LocalDate.of(2026, 7, 28)),
                jobs.missed);
    }

    @Test
    void longDowntimeRecordsEveryExpiredDateBeforeEnqueuingRecoverableDates() {
        var jobs = new FakeJobs();
        var scheduler = new ResponsibilityReconciliationScheduler(
                jobs,
                KEY,
                () -> {
                    throw new AssertionError(
                            "explicit catchUp instant is used");
                },
                LocalDate.of(2026, 7, 25));

        scheduler.catchUp(
                Instant.parse("2026-07-29T22:00:00Z"));

        assertEquals(
                List.of(
                        LocalDate.of(2026, 7, 25),
                        LocalDate.of(2026, 7, 26),
                        LocalDate.of(2026, 7, 27),
                        LocalDate.of(2026, 7, 28)),
                jobs.missed);
        assertEquals(
                List.of(
                        LocalDate.of(2026, 7, 29),
                        LocalDate.of(2026, 7, 30)),
                jobs.enqueued);
    }

    @Test
    void laterScheduledDateDoesNotHideAnEarlierGap() {
        var jobs = new FakeJobs();
        jobs.terminal.add(LocalDate.of(2026, 7, 30));
        var scheduler = scheduler(
                jobs, LocalDate.of(2026, 7, 28));

        scheduler.catchUp(
                Instant.parse("2026-07-30T22:00:00Z"));

        assertEquals(
                List.of(
                        LocalDate.of(2026, 7, 28),
                        LocalDate.of(2026, 7, 29)),
                jobs.missed);
        assertEquals(
                List.of(LocalDate.of(2026, 7, 31)),
                jobs.enqueued);
    }

    private static ResponsibilityReconciliationScheduler scheduler(
            FakeJobs jobs, LocalDate firstBusinessDate) {
        return new ResponsibilityReconciliationScheduler(
                jobs,
                KEY,
                () -> {
                    throw new AssertionError(
                            "explicit catchUp instant is used");
                },
                firstBusinessDate);
    }

    private static final class FakeJobs
            implements ResponsibilityReconciliationJobPort {
        private final List<LocalDate> enqueued =
                new ArrayList<>();
        private final List<LocalDate> missed =
                new ArrayList<>();
        private final List<LocalDate> terminal =
                new ArrayList<>();

        @Override
        public boolean terminalRunExists(
                CheckpointKey key, LocalDate businessDate) {
            return terminal.contains(businessDate)
                    || missed.contains(businessDate);
        }

        @Override
        public Optional<LocalDate> latestScheduledBusinessDate(
                CheckpointKey key) {
            return java.util.stream.Stream.of(
                            enqueued.stream(),
                            missed.stream(),
                            terminal.stream())
                    .flatMap(stream -> stream)
                    .max(LocalDate::compareTo);
        }

        @Override
        public void enqueue(
                CheckpointKey key,
                LocalDate businessDate,
                String traceId) {
            if (!enqueued.contains(businessDate)) {
                enqueued.add(businessDate);
            }
        }

        @Override
        public Optional<UUID> nextDue(
                CheckpointKey key, Instant now) {
            return Optional.empty();
        }

        @Override
        public Optional<RunningResponsibilityReconciliationAttempt>
                start(UUID jobId, String leaseOwner, Instant now) {
            return Optional.empty();
        }

        @Override
        public void complete(
                RunningResponsibilityReconciliationAttempt attempt,
                ResponsibilityReconciliationResult result,
                Instant completedAt) {}

        @Override
        public void fail(
                RunningResponsibilityReconciliationAttempt attempt,
                String reasonCode,
                boolean retry,
                Instant nextAttemptAt,
                Instant failedAt) {}

        @Override
        public void miss(
                CheckpointKey key,
                LocalDate businessDate,
                String traceId,
                String reasonCode,
                Instant detectedAt) {
            if (!missed.contains(businessDate)) {
                missed.add(businessDate);
            }
        }
    }
}
