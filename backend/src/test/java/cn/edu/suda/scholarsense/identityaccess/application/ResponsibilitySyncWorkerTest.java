package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.ArrayList;
import java.util.List;
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
        when(source.fetch(
                KEY,
                ResponsibilityAuthoritySourcePort.VERSION_1,
                6,
                job.traceId())).thenReturn(batch);
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
        when(source.fetch(
                KEY,
                ResponsibilityAuthoritySourcePort.VERSION_1,
                6,
                job.traceId())).thenThrow(
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
        when(source.fetch(
                KEY,
                ResponsibilityAuthoritySourcePort.VERSION_1,
                6,
                job.traceId())).thenReturn(batch);
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
    void beforeEffectiveAtUsesTheV1RequestedReplayRange() {
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
        when(replay.nextRequested(KEY)).thenReturn(Optional.of(
                new IdentityReplayRange(4, 6)));
        NormalizedResponsibilityBatch batch =
                mock(NormalizedResponsibilityBatch.class);
        when(source.fetchRange(
                KEY,
                ResponsibilityAuthoritySourcePort.VERSION_1,
                4,
                6,
                job.traceId())).thenReturn(batch);
        IdentitySyncResult applied = new IdentitySyncResult(
                IdentitySyncOutcome.APPLIED,
                "RESPONSIBILITY_SYNC_APPLIED",
                6,
                6,
                6);
        when(sync.process(eq(batch), eq(attempt.lease()), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Consumer<IdentitySyncResult> callback =
                            invocation.getArgument(2);
                    callback.accept(applied);
                    return applied;
                });

        new ResponsibilitySyncWorker(
                jobs,
                source,
                sync,
                repository,
                ignored -> {},
                ignored -> {},
                directTransactions(),
                ResponsibilitySyncWorkerTest::trustedNow,
                replay,
                slo,
                KEY,
                NOW.plusSeconds(1))
                .runNext("responsibility-worker")
                .orElseThrow();

        verify(source).fetchRange(
                KEY,
                ResponsibilityAuthoritySourcePort.VERSION_1,
                4,
                6,
                job.traceId());
        verify(source, never()).fetch(
                eq(KEY),
                eq(ResponsibilityAuthoritySourcePort.VERSION_1),
                anyLong(),
                eq(job.traceId()));
    }

    @Test
    void atEffectiveAtAdvancesLiveV1AndIndependentV2ShadowTogether()
            throws Exception {
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
        List<IdentitySyncObservation> observations = new ArrayList<>();
        IdentitySyncJob job = job(KEY);
        RunningIdentitySyncAttempt attempt = attempt(job);
        when(jobs.nextDue(KEY, NOW)).thenReturn(Optional.of(job));
        when(jobs.start(JOB_ID, "responsibility-worker", NOW))
                .thenReturn(Optional.of(attempt));
        when(repository.v2ShadowCheckpoint(KEY)).thenReturn(Optional.of(
                new IdentityCheckpoint(
                        KEY,
                        40,
                        41,
                        8,
                        NOW.minusSeconds(60),
                        IdentitySourceHealth.HEALTHY,
                        IdentityProjectionFreshness.FRESH)));
        when(repository.v2ShadowActive(KEY)).thenReturn(false);
        NormalizedResponsibilityBatch liveBatch =
                mock(NormalizedResponsibilityBatch.class);
        NormalizedResponsibilityBatch shadowBatch =
                mock(NormalizedResponsibilityBatch.class);
        when(source.fetch(
                KEY,
                ResponsibilityAuthoritySourcePort.VERSION_1,
                6,
                job.traceId())).thenReturn(liveBatch);
        when(source.fetch(
                KEY,
                ResponsibilityAuthoritySourcePort.VERSION_2,
                41,
                job.traceId())).thenReturn(shadowBatch);
        IdentitySyncResult liveApplied = new IdentitySyncResult(
                IdentitySyncOutcome.APPLIED,
                "RESPONSIBILITY_SYNC_APPLIED",
                7,
                7,
                9);
        IdentitySyncResult shadowApplied = new IdentitySyncResult(
                IdentitySyncOutcome.APPLIED,
                "RESPONSIBILITY_SYNC_APPLIED",
                42,
                42,
                42);
        when(sync.process(liveBatch, attempt.lease()))
                .thenReturn(liveApplied);
        when(sync.process(shadowBatch, attempt.lease()))
                .thenReturn(shadowApplied);

        IdentitySyncWorkerRun run = new ResponsibilitySyncWorker(
                jobs,
                source,
                sync,
                repository,
                ignored -> {},
                observations::add,
                directTransactions(),
                ResponsibilitySyncWorkerTest::trustedNow,
                replay,
                slo,
                KEY,
                NOW)
                .runNext("responsibility-worker")
                .orElseThrow();

        assertEquals(IdentitySyncJobStatus.SUCCEEDED, run.status());
        verify(replay).nextRequested(KEY);
        verify(source).fetch(
                KEY,
                ResponsibilityAuthoritySourcePort.VERSION_1,
                6,
                job.traceId());
        verify(source).fetch(
                KEY,
                ResponsibilityAuthoritySourcePort.VERSION_2,
                41,
                job.traceId());
        verify(source, never()).fetchRange(
                any(), anyString(), anyLong(), anyLong(), any());
        verify(slo).record(liveBatch, NOW, liveApplied);
        assertEquals(2, observations.size());
        assertEquals(
                List.of(
                        ResponsibilityAuthoritySourcePort.VERSION_1,
                        ResponsibilityAuthoritySourcePort.VERSION_2),
                observations.stream()
                        .map(observation -> observation.labels().get(
                                "responsibilityContract"))
                        .toList());
    }

    @Test
    void v2FailureAuditAndMetricCarryTheNegotiatedContract() {
        IdentitySyncJobPort jobs = mock(IdentitySyncJobPort.class);
        ResponsibilityAuthoritySourcePort source =
                mock(ResponsibilityAuthoritySourcePort.class);
        ResponsibilitySyncService sync =
                mock(ResponsibilitySyncService.class);
        ResponsibilitySyncRepository repository =
                mock(ResponsibilitySyncRepository.class);
        IdentityReplayPort replay = mock(IdentityReplayPort.class);
        List<IdentitySyncAuditEvent> audits = new ArrayList<>();
        List<IdentitySyncObservation> observations = new ArrayList<>();
        IdentitySyncJob job = job(KEY);
        RunningIdentitySyncAttempt attempt = attempt(job);
        when(jobs.nextDue(KEY, NOW)).thenReturn(Optional.of(job));
        when(jobs.start(JOB_ID, "responsibility-worker", NOW))
                .thenReturn(Optional.of(attempt));
        when(repository.v2ShadowCheckpoint(KEY)).thenReturn(Optional.of(
                IdentityCheckpoint.initial(KEY)));
        when(repository.v2ShadowActive(KEY)).thenReturn(false);
        NormalizedResponsibilityBatch liveBatch =
                mock(NormalizedResponsibilityBatch.class);
        when(source.fetch(
                KEY,
                ResponsibilityAuthoritySourcePort.VERSION_1,
                6,
                job.traceId())).thenReturn(liveBatch);
        when(sync.process(liveBatch, attempt.lease())).thenReturn(
                new IdentitySyncResult(
                        IdentitySyncOutcome.APPLIED,
                        "RESPONSIBILITY_SYNC_APPLIED",
                        7,
                        7,
                        7));
        when(source.fetch(
                KEY,
                ResponsibilityAuthoritySourcePort.VERSION_2,
                0,
                job.traceId())).thenThrow(new IdentitySyncException(
                        "RESPONSIBILITY_SOURCE_DEPENDENCY_UNAVAILABLE"));

        IdentitySyncWorkerRun run = new ResponsibilitySyncWorker(
                jobs,
                source,
                sync,
                repository,
                audits::add,
                observations::add,
                directTransactions(),
                ResponsibilitySyncWorkerTest::trustedNow,
                replay,
                KEY,
                NOW)
                .runNext("responsibility-worker")
                .orElseThrow();

        assertEquals(IdentitySyncJobStatus.QUEUED, run.status());
        assertEquals(
                ResponsibilityAuthoritySourcePort.VERSION_2,
                audits.getFirst().policyVersions().get(
                        "responsibilityContract"));
        assertEquals(
                ResponsibilityAuthoritySourcePort.VERSION_2,
                observations.getLast().labels().get(
                        "responsibilityContract"));
        verify(replay).nextRequested(KEY);
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
        verify(source, never()).fetch(
                any(), anyString(), anyLong(), any());
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

    private static IdentitySyncTransactionPort directTransactions() {
        return new IdentitySyncTransactionPort() {
            @Override
            public <T> T execute(
                    java.util.function.Supplier<T> work) {
                return work.get();
            }
        };
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
