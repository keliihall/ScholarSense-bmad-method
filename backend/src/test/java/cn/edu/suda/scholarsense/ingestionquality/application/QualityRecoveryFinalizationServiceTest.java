package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecisionToken;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalView;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskApprovalPort;
import cn.edu.suda.scholarsense.identityaccess.api.HighRiskExecutionAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.RecoveryCheckerBindingResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QualityRecoveryFinalizationServiceTest {
    private static final UUID RECOVERY_ID =
            UUID.fromString("018f34c0-9b80-7a11-8abc-0123456789ab");
    private static final UUID TASK_ID =
            UUID.fromString("018f34c0-9b81-7a11-8abc-0123456789ab");
    private static final String WATERMARK = "wm-final-42";
    private QualityRecoveryFinalizationStorePort store;
    private QualityRecoveryAuthorizationGuard authorization;
    private RecoveryCheckerBindingResolver checkers;
    private HighRiskApprovalPort approvals;
    private HighRiskExecutionAuthorizationPort executions;
    private RecoveryValidationTrustedTimePort time;
    private Supplier<UUID> ids;
    private QualityRecoveryFinalizationService service;

    @BeforeEach
    void setUp() {
        store = mock(QualityRecoveryFinalizationStorePort.class);
        authorization = mock(QualityRecoveryAuthorizationGuard.class);
        checkers = mock(RecoveryCheckerBindingResolver.class);
        approvals = mock(HighRiskApprovalPort.class);
        executions = mock(HighRiskExecutionAuthorizationPort.class);
        time = mock(RecoveryValidationTrustedTimePort.class);
        @SuppressWarnings("unchecked")
        Supplier<UUID> mockedIds = mock(Supplier.class);
        ids = mockedIds;
        service = new QualityRecoveryFinalizationService(
                store, authorization, checkers, approvals, executions, time, ids);
        QualityRecoveryFinalizationContext context =
                mock(QualityRecoveryFinalizationContext.class);
        when(context.taskId()).thenReturn(TASK_ID);
        when(context.taskVersion()).thenReturn(99L);
        when(store.load(RECOVERY_ID)).thenReturn(Optional.of(context));
        when(authorization.authorize(any())).thenReturn(
                mock(CompositeAuthorizationDecision.class));
    }

    @Test
    void terminalReplayPrecedesStaleVersionChecksAndNeverIssuesANewLease() {
        QualityRecoveryFinalizationCommit committed =
                mock(QualityRecoveryFinalizationCommit.class);
        when(store.findReplay(anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(RECOVERY_ID),
                org.mockito.ArgumentMatchers.eq(WATERMARK)))
                .thenReturn(Optional.of(committed));

        QualityRecoveryFinalizationCommit replay = service.execute(
                command("final-replay-key-0001"), actor(), traceId());

        assertSame(committed, replay);
        verify(store, never()).execute(anyString(), any());
        verifyNoInteractions(checkers, approvals, executions, time);
    }

    @Test
    void idempotencyConflictPropagatesBeforeLeaseIssuance() {
        IllegalStateException conflict = new IllegalStateException(
                "INGESTION_QUALITY_FINALIZATION_IDEMPOTENCY_CONFLICT");
        when(store.findReplay(anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(RECOVERY_ID),
                org.mockito.ArgumentMatchers.eq(WATERMARK)))
                .thenThrow(conflict);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.execute(
                        command("final-replay-key-0002"), actor(), traceId()));

        assertSame(conflict, thrown);
        verify(store, never()).execute(anyString(), any());
        verifyNoInteractions(checkers, approvals, executions, time);
    }

    @Test
    void approvalRequestResponseLossReplaysAfterOwnerBindingWasCommitted() {
        UUID approvalId = UUID.fromString("018f34c0-9b83-7a11-8abc-0123456789ab");
        QualityRecoveryFinalizationContext pending = context(
                "approval-pending", approvalId, 1L, null);
        when(store.load(RECOVERY_ID)).thenReturn(Optional.of(pending));
        stubApprovalDependencies();
        when(approvals.request(any())).thenReturn(new HighRiskApprovalView(
                approvalId, 1, UUID.fromString("018f34c0-9b84-7a11-8abc-0123456789ab"),
                "pending", 1, 0, now(), now().plusSeconds(14_400), null, traceId()));
        when(store.bindApproval(any())).thenReturn(pending);

        QualityRecoveryFinalizationContext replay = service.requestApproval(
                command("final-request-replay-0001"), actor(), traceId());

        assertSame(pending, replay);
        verify(approvals).request(any());
        verify(store).bindApproval(any());
    }

    @Test
    void approvalDecisionResponseLossReplaysAfterOwnerBindingWasCommitted() {
        UUID approvalId = UUID.fromString("018f34c0-9b85-7a11-8abc-0123456789ab");
        String receipt = "sha256:" + "9".repeat(64);
        QualityRecoveryFinalizationContext approved = context(
                "approval-approved", approvalId, 2L, receipt);
        when(store.load(RECOVERY_ID)).thenReturn(Optional.of(approved));
        stubApprovalDependencies();
        when(approvals.decide(any())).thenReturn(new HighRiskApprovalView(
                approvalId, 2, UUID.fromString("018f34c0-9b86-7a11-8abc-0123456789ab"),
                "approved", 1, 1, now(), now().plusSeconds(14_400), receipt, traceId()));
        when(store.bindApproval(any())).thenReturn(approved);

        QualityRecoveryFinalizationContext replay = service.decide(
                RECOVERY_ID, 1, "approve", "final-decision-replay-0001",
                checker(), traceId());

        assertSame(approved, replay);
        verify(approvals).decide(any());
        verify(store).bindApproval(any());
    }

    @Test
    void rejectedApprovalCanBeExplicitlyRequestedAgainWithFreshIdentityEvidence() {
        UUID rejectedApprovalId = UUID.fromString("018f34c0-9b90-7a11-8abc-0123456789ab");
        UUID freshApprovalId = UUID.fromString("018f34c0-9b91-7a11-8abc-0123456789ab");
        QualityRecoveryFinalizationContext rejected = context(
                "approval-rejected", rejectedApprovalId, 2L,
                "sha256:" + "8".repeat(64));
        QualityRecoveryFinalizationContext pending = context(
                "approval-pending", freshApprovalId, 1L, null);
        when(store.load(RECOVERY_ID)).thenReturn(Optional.of(rejected));
        stubApprovalDependencies();
        when(approvals.request(any())).thenReturn(new HighRiskApprovalView(
                freshApprovalId, 1,
                UUID.fromString("018f34c0-9b92-7a11-8abc-0123456789ab"),
                "pending", 1, 0, now(), now().plusSeconds(14_400), null, traceId()));
        when(store.bindApproval(any())).thenReturn(pending);

        QualityRecoveryFinalizationContext restarted = service.requestApproval(
                command("final-rerequest-key-0001"), actor(), traceId());

        assertSame(pending, restarted);
        verify(approvals).request(any());
    }

    private void stubApprovalDependencies() {
        when(ids.get()).thenReturn(
                UUID.fromString("018f34c0-9b87-7a11-8abc-0123456789ab"));
        when(time.now()).thenReturn(now());
        when(authorization.authorize(any())).thenReturn(decision());
        when(checkers.resolve(any(), org.mockito.ArgumentMatchers.nullable(String.class),
                any(), anyString())).thenReturn(new RecoveryCheckerBindingResolver.Resolution(
                        true, List.of("sha256:" + "b".repeat(64)),
                        "sha256:" + "c".repeat(64), "sha256:" + "d".repeat(64),
                        "sha256:" + "e".repeat(64), null));
    }

    private static QualityRecoveryFinalizationContext context(
            String state, UUID approvalId, Long approvalVersion, String receipt) {
        String digest = "sha256:" + "1".repeat(64);
        return new QualityRecoveryFinalizationContext(
                RECOVERY_ID, 1, TASK_ID, 1,
                UUID.fromString("018f34c0-9b88-7a11-8abc-0123456789ab"), 1, 1,
                "SRC-P0-CAMPUS-ACCESS-001", "DEP-P0-CAMPUS-ACCESS-001",
                "open", true, "ready", 3, digest, WATERMARK,
                "QRP-1.0.0", digest, digest, digest, digest, state,
                approvalId, approvalVersion, receipt, "sha256:" + "e".repeat(64),
                "sha256:" + "c".repeat(64), "sha256:" + "d".repeat(64),
                digest, digest, 7, actor().naturalPersonPrincipalDigest(), digest,
                digest, digest, List.of(new QualityRecoveryFinalizationContext.AffectedRule(
                        "ACC-SAFE-001", "1.0.0", digest)), traceId());
    }

    private static CompositeAuthorizationDecision decision() {
        return new CompositeAuthorizationDecision(
                CompositeAuthorizationOutcome.ALLOW, "AUTHORIZED",
                Set.of("R6-DATA-OWNER"), Set.of("OWNED_SOURCE"), Map.of(), Set.of(),
                "RFP-1.0.0", 1, now(),
                new CompositeAuthorizationDecisionToken(1, 1, 1, 7, 1, 1,
                        "RFP-1.0.0"));
    }

    private static QualityRecoveryFinalizationCommand command(String key) {
        return new QualityRecoveryFinalizationCommand(
                RECOVERY_ID, 1, 1, WATERMARK, key);
    }

    private static QualityRecoveryCommandActor actor() {
        return new QualityRecoveryCommandActor(
                "session-pseudonym", "actor-pseudonym",
                UUID.fromString("018f34c0-9b82-7a11-8abc-0123456789ab"),
                "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                Set.of("R6-DATA-OWNER"), 7,
                Instant.parse("2026-08-15T00:00:00Z"), "IDENTITY-PROFILE-1.0.0");
    }

    private static QualityRecoveryCommandActor checker() {
        return new QualityRecoveryCommandActor(
                "session-checker", "actor-checker",
                UUID.fromString("018f34c0-9b89-7a11-8abc-0123456789ab"),
                "sha256:" + "b".repeat(64), "sha256:" + "f".repeat(64),
                Set.of("R6-DATA-OWNER"), 7,
                Instant.parse("2026-08-15T00:00:00Z"), "IDENTITY-PROFILE-1.0.0");
    }

    private static Instant now() {
        return Instant.parse("2026-08-14T23:00:00Z");
    }

    private static String traceId() {
        return "00112233445566778899aabbccddeeff";
    }
}
