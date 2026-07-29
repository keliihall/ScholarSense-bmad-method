package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncJobService;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncWorker;
import org.junit.jupiter.api.Test;

class IdentitySyncSchedulerTest {
    @Test
    void schedulerOnlyInvokesPersistedJobUseCases() {
        IdentitySyncJobService jobs = mock(IdentitySyncJobService.class);
        IdentitySyncWorker worker = mock(IdentitySyncWorker.class);
        CheckpointKey key = new CheckpointKey(
                "SRC-P0-RESPONSIBILITY-001",
                "identity-authority",
                "sandbox-0",
                "identity-org");
        var scheduler = new IdentitySyncScheduler(jobs, worker, key, 5);

        scheduler.poll();

        verify(jobs).ensureRequested(
                org.mockito.ArgumentMatchers.eq(key),
                org.mockito.ArgumentMatchers.eq(5),
                anyString());
        verify(worker).runNext(anyString());
    }
}
