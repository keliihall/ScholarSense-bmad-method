package cn.edu.suda.scholarsense.ingestionquality.adapters.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotRetentionOrchestrator;
import cn.edu.suda.scholarsense.shared.observability.ObservationPort;
import cn.edu.suda.scholarsense.shared.observability.SafeObservationAttributes;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContext;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContextCodec;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QualitySnapshotRetentionSchedulerTest {
    @Test
    void pureWorkerScheduleCreatesAnAttemptChildAndCarriesItsTraceIntoDurableWork() {
        W3cTraceContext parent = new W3cTraceContext(
                "1234567890abcdef1234567890abcdef", "1111111111111111", true);
        W3cTraceContext child = new W3cTraceContext(
                parent.traceId(), "2222222222222222", true);
        QualitySnapshotRetentionOrchestrator orchestrator =
                mock(QualitySnapshotRetentionOrchestrator.class);
        ObservationPort observations = mock(ObservationPort.class);
        ObservationPort.ObservationScope scope = mock(ObservationPort.ObservationScope.class);
        when(observations.start(
                eq("job.attempt"), eq(ObservationPort.ObservationKind.INTERNAL),
                any(SafeObservationAttributes.class), eq(parent))).thenReturn(scope);
        when(scope.context()).thenReturn(child);

        new QualitySnapshotRetentionScheduler(
                orchestrator, () -> Optional.of(parent), new W3cTraceContextCodec(),
                observations).executeOne();

        verify(orchestrator).runOne(child.traceId());
        verify(scope).outcome("success");
        verify(scope).close();
    }

    @Test
    void failedRetentionAttemptOverwritesOutcomeBeforeRecordingError() {
        W3cTraceContext parent = new W3cTraceContext(
                "1234567890abcdef1234567890abcdef", "1111111111111111", true);
        W3cTraceContext child = new W3cTraceContext(
                parent.traceId(), "2222222222222222", true);
        QualitySnapshotRetentionOrchestrator orchestrator =
                mock(QualitySnapshotRetentionOrchestrator.class);
        ObservationPort observations = mock(ObservationPort.class);
        ObservationPort.ObservationScope scope = mock(ObservationPort.ObservationScope.class);
        RuntimeException failure = new IllegalStateException("owner unavailable");
        when(observations.start(
                eq("job.attempt"), eq(ObservationPort.ObservationKind.INTERNAL),
                any(SafeObservationAttributes.class), eq(parent))).thenReturn(scope);
        when(scope.context()).thenReturn(child);
        doThrow(failure).when(orchestrator).runOne(child.traceId());

        QualitySnapshotRetentionScheduler scheduler = new QualitySnapshotRetentionScheduler(
                orchestrator, () -> Optional.of(parent), new W3cTraceContextCodec(),
                observations);

        assertThrows(IllegalStateException.class, scheduler::executeOne);
        verify(scope).outcome("failure");
        verify(scope).error(failure);
        verify(scope).close();
    }
}
