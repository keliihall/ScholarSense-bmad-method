package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityReconciliationWorker;
import org.junit.jupiter.api.Test;

class ResponsibilityReconciliationWorkerSchedulerTest {
    @Test
    void productionPollInvokesDurableWorkerWithControlledLeaseOwner() {
        ResponsibilityReconciliationWorker worker =
                mock(ResponsibilityReconciliationWorker.class);

        new ResponsibilityReconciliationWorkerScheduler(worker)
                .poll();

        verify(worker).runNext(matches(
                "responsibility-reconciliation-[0-9a-f]{16}"));
    }
}
