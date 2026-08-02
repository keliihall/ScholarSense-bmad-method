package cn.edu.suda.scholarsense.identityaccess.adapters.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySyncRepository;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2CutoverService;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityV2CutoverCommand;
import cn.edu.suda.scholarsense.runtime.ResponsibilityAuthorityRuntimeProfile;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ResponsibilityV2CutoverSchedulerTest {
    private static final CheckpointKey KEY = new CheckpointKey(
            "SRC-P0-RESPONSIBILITY-001",
            "responsibility-authority",
            "sandbox-0",
            "responsibility");

    @Test
    void activatesOnlyAfterEffectiveBoundaryAndUsesShanghaiBusinessDate() {
        ResponsibilityV2CutoverService cutover =
                mock(ResponsibilityV2CutoverService.class);
        ResponsibilitySyncRepository repository =
                mock(ResponsibilitySyncRepository.class);
        when(repository.v2ShadowActive(KEY)).thenReturn(false);

        new ResponsibilityV2CutoverScheduler(
                cutover,
                repository,
                () -> trusted(Instant.parse("2026-07-31T15:59:59Z")),
                profile(),
                KEY,
                ignored -> "d".repeat(64)).reconcileAndActivate();
        verify(cutover, never()).execute(any());

        new ResponsibilityV2CutoverScheduler(
                cutover,
                repository,
                () -> trusted(Instant.parse("2026-07-31T16:00:00Z")),
                profile(),
                KEY,
                ignored -> "d".repeat(64)).reconcileAndActivate();

        ArgumentCaptor<ResponsibilityV2CutoverCommand> request =
                ArgumentCaptor.forClass(
                        ResponsibilityV2CutoverCommand.class);
        verify(cutover).execute(request.capture());
        assertEquals(KEY, request.getValue().key());
        assertEquals(
                LocalDate.of(2026, 8, 1),
                request.getValue().businessDate());
        assertEquals(
                ResponsibilityV2CutoverCommand.CONTROLLED_OPERATOR,
                request.getValue().operatorRef());
        assertEquals(
                profile().contractProfileDigest(),
                request.getValue().profileDigest());
        assertEquals("d".repeat(64),
                request.getValue().signatureDigest());
    }

    @Test
    void activeShadowMakesScheduledCutoverIdempotent() {
        ResponsibilityV2CutoverService cutover =
                mock(ResponsibilityV2CutoverService.class);
        ResponsibilitySyncRepository repository =
                mock(ResponsibilitySyncRepository.class);
        when(repository.v2ShadowActive(KEY)).thenReturn(true);

        new ResponsibilityV2CutoverScheduler(
                cutover,
                repository,
                () -> trusted(Instant.parse("2026-08-01T01:00:00Z")),
                profile(),
                KEY,
                ignored -> "d".repeat(64)).reconcileAndActivate();

        verify(cutover, never()).execute(any());
    }

    private static ResponsibilityAuthorityRuntimeProfile profile() {
        return new ResponsibilityAuthorityRuntimeProfile(
                KEY.sourceId(),
                KEY.feedId(),
                KEY.partitionId(),
                KEY.consumerProjection(),
                URI.create("https://test.example.invalid/api/v1/incremental"),
                Duration.ofSeconds(20),
                "account://test/responsibility-sync-worker",
                "secret://test/responsibility-signature",
                "config://test/responsibility-inbox",
                false);
    }

    private static TrustedTime trusted(Instant instant) {
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
