package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ResponsibilitySyncWorkerTest {
    private static final Instant NOW =
            Instant.parse("2026-07-30T00:00:00Z");
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "responsibility-authority",
            "sandbox-0",
            "responsibility");
    private static final UUID JOB_ID =
            UUID.fromString("019c1234-0000-7000-8000-000000000601");

    @Test
    void projectionScopedWorkerCompletesJobInsideServiceCallback() {
        IdentitySyncJobPort jobs = mock(IdentitySyncJobPort.class);
        ResponsibilityAuthoritySourcePort source =
                mock(ResponsibilityAuthoritySourcePort.class);
        ResponsibilitySyncService sync =
                mock(ResponsibilitySyncService.class);
        ResponsibilitySyncRepository repository =
                mock(ResponsibilitySyncRepository.class);
        IdentitySyncJob job = job(KEY);
        RunningIdentitySyncAttempt attempt = attempt(job);
        when(jobs.nextDue(KEY, NOW)).thenReturn(Optional.of(job));
        when(jobs.start(JOB_ID, "responsibility-worker", NOW))
                .thenReturn(Optional.of(attempt));
        NormalizedResponsibilityBatch batch =
                mock(NormalizedResponsibilityBatch.class);
        when(source.fetch(KEY, 6, job.traceId())).thenReturn(batch);
        IdentitySyncResult applied = new IdentitySyncResult(
                IdentitySyncOutcome.APPLIED,
                "RESPONSIBILITY_SYNC_APPLIED",
                7,
                7,
                7);
        when(sync.process(eq(batch), eq(attempt.lease()), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<IdentitySyncResult> callback =
                            invocation.getArgument(2);
                    callback.accept(applied);
                    return applied;
                });
        ResponsibilitySloRecorder slo =
                mock(ResponsibilitySloRecorder.class);
        AtomicInteger clockReads = new AtomicInteger();

        IdentitySyncWorkerRun run = worker(
                jobs,
                source,
                sync,
                repository,
                slo,
                () -> trustedAt(
                        clockReads.getAndIncrement() == 0
                                ? NOW
                                : NOW.plusSeconds(120)))
                .runNext("responsibility-worker")
                .orElseThrow();

        assertEquals(IdentitySyncJobStatus.SUCCEEDED, run.status());
        assertEquals(7, run.lastSuccessfulWatermark());
        ArgumentCaptor<IdentitySyncJob> completed =
                ArgumentCaptor.forClass(IdentitySyncJob.class);
        verify(jobs).save(eq(attempt), completed.capture(), eq(NOW));
        assertEquals(
                IdentitySyncJobStatus.SUCCEEDED,
                completed.getValue().status());
        verify(slo).record(
                batch,
                NOW.plusSeconds(120),
                applied);
    }

    @Test
    void dependencyFailureUsesPersistedRetryBudgetAndBackoff() {
        IdentitySyncJobPort jobs = mock(IdentitySyncJobPort.class);
        ResponsibilityAuthoritySourcePort source =
                mock(ResponsibilityAuthoritySourcePort.class);
        ResponsibilitySyncService sync =
                mock(ResponsibilitySyncService.class);
        ResponsibilitySyncRepository repository =
                mock(ResponsibilitySyncRepository.class);
        IdentitySyncJob job = job(KEY);
        RunningIdentitySyncAttempt attempt = attempt(job);
        when(jobs.nextDue(KEY, NOW)).thenReturn(Optional.of(job));
        when(jobs.start(JOB_ID, "responsibility-worker", NOW))
                .thenReturn(Optional.of(attempt));
        when(source.fetch(KEY, 6, job.traceId())).thenThrow(
                new IdentitySyncException(
                        "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE"));

        IdentitySyncWorkerRun run = worker(
                jobs, source, sync, repository)
                .runNext("responsibility-worker")
                .orElseThrow();

        assertEquals(IdentitySyncJobStatus.QUEUED, run.status());
        ArgumentCaptor<IdentitySyncJob> saved =
                ArgumentCaptor.forClass(IdentitySyncJob.class);
        verify(jobs).save(eq(attempt), saved.capture(), eq(NOW));
        assertEquals(NOW.plusSeconds(30), saved.getValue().nextAttemptAt());
        assertEquals(
                "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE",
                saved.getValue().reasonCode());
    }

    @Test
    void committedSyncSchedulesExactReplayWhenSloPersistenceDoubleFails() {
        IdentitySyncJobPort jobs = mock(IdentitySyncJobPort.class);
        ResponsibilityAuthoritySourcePort source =
                mock(ResponsibilityAuthoritySourcePort.class);
        ResponsibilitySyncService sync =
                mock(ResponsibilitySyncService.class);
        ResponsibilitySyncRepository repository =
                mock(ResponsibilitySyncRepository.class);
        IdentityReplayPort replay = mock(IdentityReplayPort.class);
        ResponsibilitySloRecorder slo =
                mock(ResponsibilitySloRecorder.class);
        IdentitySyncJob job = job(KEY);
        RunningIdentitySyncAttempt attempt = attempt(job);
        when(jobs.nextDue(KEY, NOW)).thenReturn(Optional.of(job));
        when(jobs.start(JOB_ID, "responsibility-worker", NOW))
                .thenReturn(Optional.of(attempt));
        NormalizedResponsibilityBatch batch =
                mock(NormalizedResponsibilityBatch.class);
        when(batch.key()).thenReturn(KEY);
        when(batch.fromWatermark()).thenReturn(6L);
        when(batch.toWatermark()).thenReturn(7L);
        when(batch.traceId()).thenReturn(job.traceId());
        when(source.fetch(KEY, 6, job.traceId())).thenReturn(batch);
        IdentitySyncResult applied = new IdentitySyncResult(
                IdentitySyncOutcome.APPLIED,
                "RESPONSIBILITY_SYNC_APPLIED",
                7,
                7,
                7);
        when(sync.process(eq(batch), eq(attempt.lease()), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<IdentitySyncResult> callback =
                            invocation.getArgument(2);
                    callback.accept(applied);
                    return applied;
                });
        org.mockito.Mockito.doThrow(new IdentitySyncException(
                        "RESPONSIBILITY_SLO_COMPENSATION_PERSISTENCE_UNAVAILABLE"))
                .when(slo)
                .record(batch, NOW.plusSeconds(120), applied);
        AtomicInteger clockReads = new AtomicInteger();
        IdentitySyncTransactionPort direct =
                new IdentitySyncTransactionPort() {
                    @Override
                    public <T> T execute(
                            java.util.function.Supplier<T> work) {
                        return work.get();
                    }
                };
        var worker = new ResponsibilitySyncWorker(
                jobs,
                source,
                sync,
                repository,
                ignored -> {},
                ignored -> {},
                direct,
                () -> trustedAt(
                        clockReads.getAndIncrement() == 0
                                ? NOW
                                : NOW.plusSeconds(120)),
                replay,
                slo,
                KEY);

        IdentitySyncWorkerRun run =
                worker.runNext("responsibility-worker")
                        .orElseThrow();

        assertEquals(IdentitySyncJobStatus.SUCCEEDED, run.status());
        verify(jobs, times(1)).save(
                eq(attempt), any(), eq(NOW));
        verify(replay).request(
                KEY, 7, 7, job.traceId());
        ArgumentCaptor<IdentitySyncJob> retry =
                ArgumentCaptor.forClass(IdentitySyncJob.class);
        verify(jobs).enqueueIfEligible(retry.capture());
        assertEquals(
                IdentitySyncJobStatus.QUEUED,
                retry.getValue().status());
        assertEquals(
                "RESPONSIBILITY_SLO_COMPENSATION_PERSISTENCE_UNAVAILABLE",
                retry.getValue().reasonCode());
        assertEquals(7, retry.getValue().lastSuccessfulWatermark());
    }

    @Test
    void responsibilityWorkerNeverClaimsIdentityOrgProjection() {
        IdentitySyncJobPort jobs = mock(IdentitySyncJobPort.class);
        ResponsibilityAuthoritySourcePort source =
                mock(ResponsibilityAuthoritySourcePort.class);
        ResponsibilitySyncService sync =
                mock(ResponsibilitySyncService.class);
        ResponsibilitySyncRepository repository =
                mock(ResponsibilitySyncRepository.class);
        when(jobs.nextDue(KEY, NOW)).thenReturn(Optional.empty());

        assertTrue(worker(jobs, source, sync, repository)
                .runNext("responsibility-worker")
                .isEmpty());
        verify(jobs, never()).start(any(), any(), any());
        verify(source, never()).fetch(any(), anyLong(), any());
    }

    private static ResponsibilitySyncWorker worker(
            IdentitySyncJobPort jobs,
            ResponsibilityAuthoritySourcePort source,
            ResponsibilitySyncService sync,
            ResponsibilitySyncRepository repository) {
        IdentitySyncTransactionPort direct =
                new IdentitySyncTransactionPort() {
                    @Override
                    public <T> T execute(
                            java.util.function.Supplier<T> work) {
                        return work.get();
                    }
                };
        return new ResponsibilitySyncWorker(
                jobs,
                source,
                sync,
                repository,
                ignored -> {},
                ignored -> {},
                direct,
                ResponsibilitySyncWorkerTest::trustedNow,
                (key, from, to, trace) -> {},
                KEY);
    }

    private static ResponsibilitySyncWorker worker(
            IdentitySyncJobPort jobs,
            ResponsibilityAuthoritySourcePort source,
            ResponsibilitySyncService sync,
            ResponsibilitySyncRepository repository,
            ResponsibilitySloRecorder slo,
            cn.edu.suda.scholarsense.shared.time.TrustedTimeSource time) {
        IdentitySyncTransactionPort direct =
                new IdentitySyncTransactionPort() {
                    @Override
                    public <T> T execute(
                            java.util.function.Supplier<T> work) {
                        return work.get();
                    }
                };
        return new ResponsibilitySyncWorker(
                jobs,
                source,
                sync,
                repository,
                ignored -> {},
                ignored -> {},
                direct,
                time,
                (key, from, to, trace) -> {},
                slo,
                KEY);
    }

    private static IdentitySyncJob job(CheckpointKey key) {
        return new IdentitySyncJob(
                JOB_ID,
                key,
                IdentitySyncJobStatus.QUEUED,
                IdentitySourceHealth.HEALTHY,
                IdentityProjectionFreshness.FRESH,
                NOW.minusSeconds(60),
                null,
                6,
                null,
                3,
                0,
                null,
                "0123456789abcdef0123456789abcdef");
    }

    private static RunningIdentitySyncAttempt attempt(
            IdentitySyncJob job) {
        IdentitySyncJob running = job.transitionTo(
                IdentitySyncJobStatus.RUNNING,
                job.health(),
                job.freshness(),
                null,
                null,
                null,
                job.lastSuccessfulWatermark());
        return new RunningIdentitySyncAttempt(
                running,
                1,
                new IdentityLease(
                        KEY,
                        JOB_ID,
                        1,
                        9,
                        "responsibility-worker",
                        NOW.minusSeconds(1),
                        NOW.plus(Duration.ofMinutes(2))),
                6,
                NOW);
    }

    private static TrustedTime trustedNow() {
        return trustedAt(NOW);
    }

    private static TrustedTime trustedAt(Instant instant) {
        return new TrustedTime(
                instant,
                new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        instant.minusSeconds(10),
                        instant.plusSeconds(50),
                        "evidence://signed/clock/campus-ntp-a.json"));
    }
}
