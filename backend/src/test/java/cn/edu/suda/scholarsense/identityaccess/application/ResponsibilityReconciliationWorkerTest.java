package cn.edu.suda.scholarsense.identityaccess.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResponsibilityReconciliationWorkerTest {
    private static final Instant NOW =
            Instant.parse("2026-07-30T00:00:00Z");
    private static final LocalDate DATE =
            LocalDate.of(2026, 7, 30);
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "responsibility-authority",
            "sandbox-0",
            "responsibility");
    private static final UUID JOB_ID = UUID.fromString(
            "019c1234-0000-7000-8000-000000000805");

    @Test
    void failedSnapshotAttemptAndRejectedAuditCommitTogether() {
        ResponsibilityReconciliationJobPort jobs =
                mock(ResponsibilityReconciliationJobPort.class);
        ResponsibilityReconciliationService service =
                mock(ResponsibilityReconciliationService.class);
        var attempt = attempt();
        when(jobs.nextDue(KEY, NOW))
                .thenReturn(Optional.of(JOB_ID));
        when(jobs.start(JOB_ID, "reconciliation-worker", NOW))
                .thenReturn(Optional.of(attempt));
        when(service.activeContractVersion(KEY))
                .thenReturn(ResponsibilityAuthoritySourcePort.VERSION_1);
        when(service.execute(
                        eq(attempt),
                        eq(ResponsibilityAuthoritySourcePort.VERSION_1),
                        any()))
                .thenThrow(new IdentitySyncException(
                        "RESPONSIBILITY_SNAPSHOT_PARTITION_MISSING"));
        var audits = new ArrayList<IdentitySyncAuditEvent>();
        IdentitySyncTransactionPort direct =
                new IdentitySyncTransactionPort() {
                    @Override
                    public <T> T execute(
                            java.util.function.Supplier<T> work) {
                        return work.get();
                    }
                };
        var worker = new ResponsibilityReconciliationWorker(
                jobs,
                service,
                direct,
                ResponsibilityReconciliationWorkerTest::trustedNow,
                audits::add,
                KEY);

        assertTrue(worker.runNext(
                "reconciliation-worker").isEmpty());

        verify(jobs).fail(
                attempt,
                "RESPONSIBILITY_SNAPSHOT_PARTITION_MISSING",
                true,
                NOW.plusSeconds(30),
                NOW);
        assertEquals(1, audits.size());
        assertEquals(
                "responsibility.sync.failed",
                audits.getFirst().action());
        assertEquals(9, audits.getFirst().fencingToken());
        assertEquals(
                "RESPONSIBILITY_SNAPSHOT_PARTITION_MISSING",
                audits.getFirst().reasonCode());
    }

    @Test
    void contractCutoverRaceRetriesAndAuditsAttemptedVersion() {
        ResponsibilityReconciliationJobPort jobs =
                mock(ResponsibilityReconciliationJobPort.class);
        ResponsibilityReconciliationService service =
                mock(ResponsibilityReconciliationService.class);
        var attempt = attempt();
        when(jobs.nextDue(KEY, NOW))
                .thenReturn(Optional.of(JOB_ID));
        when(jobs.start(JOB_ID, "reconciliation-worker", NOW))
                .thenReturn(Optional.of(attempt));
        when(service.activeContractVersion(KEY))
                .thenReturn(ResponsibilityAuthoritySourcePort.VERSION_2);
        when(service.execute(
                        eq(attempt),
                        eq(ResponsibilityAuthoritySourcePort.VERSION_2),
                        any()))
                .thenThrow(new IdentitySyncException(
                        "RESPONSIBILITY_RECONCILIATION_CONTRACT_STALE"));
        var audits = new ArrayList<IdentitySyncAuditEvent>();
        IdentitySyncTransactionPort direct = new IdentitySyncTransactionPort() {
            @Override
            public <T> T execute(java.util.function.Supplier<T> work) {
                return work.get();
            }
        };
        var worker = new ResponsibilityReconciliationWorker(
                jobs,
                service,
                direct,
                ResponsibilityReconciliationWorkerTest::trustedNow,
                audits::add,
                KEY);

        assertTrue(worker.runNext("reconciliation-worker").isEmpty());

        verify(jobs).fail(
                attempt,
                "RESPONSIBILITY_RECONCILIATION_CONTRACT_STALE",
                true,
                NOW.plusSeconds(30),
                NOW);
        assertEquals(
                ResponsibilityAuthoritySourcePort.VERSION_2,
                audits.getFirst().policyVersions().get(
                        "responsibilityContract"));
    }

    private static RunningResponsibilityReconciliationAttempt
            attempt() {
        return new RunningResponsibilityReconciliationAttempt(
                JOB_ID,
                KEY,
                DATE,
                1,
                3,
                "0123456789abcdef0123456789abcdef",
                NOW,
                new ResponsibilityReconciliationLease(
                        KEY,
                        DATE,
                        JOB_ID,
                        1,
                        9,
                        "reconciliation-worker",
                        NOW.minusSeconds(1),
                        NOW.plus(Duration.ofMinutes(2))));
    }

    private static TrustedTime trustedNow() {
        return new TrustedTime(
                NOW,
                new TimeSourceProfile(
                        "campus-ntp-a",
                        "AUDIT-CLOCK-BINDING-1.0.0",
                        5,
                        NOW.minusSeconds(10),
                        NOW.plusSeconds(50),
                        "evidence://signed/clock/test.json"));
    }
}
