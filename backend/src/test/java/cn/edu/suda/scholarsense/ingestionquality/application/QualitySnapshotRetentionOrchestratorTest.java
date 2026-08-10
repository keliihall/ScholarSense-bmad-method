package cn.edu.suda.scholarsense.ingestionquality.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class QualitySnapshotRetentionOrchestratorTest {
    private static final UUID AUTHORITY_ID =
            UUID.fromString("019d2c7d-4000-7000-8000-000000000210");
    private static final UUID EXECUTION_ID =
            UUID.fromString("019d2c7d-4000-7000-8000-000000000209");
    private static final String RETENTION_TRACE = "22222222222222222222222222222222";
    private static final QualitySnapshotRetentionAttemptIds ATTEMPT =
            new QualitySnapshotRetentionAttemptIds(
                    UUID.fromString("019d2c7d-4000-7000-8000-000000000211"),
                    AUTHORITY_ID);

    @Test
    void ownerFindsDueCandidateGeneratesIdsThenIngestsVerifiedAuthorityBeforeExecution() {
        QualitySnapshotRetentionCandidatePort candidates =
                mock(QualitySnapshotRetentionCandidatePort.class);
        ConsumerRegistryAuthorityPort authority = mock(ConsumerRegistryAuthorityPort.class);
        QualitySnapshotRetentionIdPort ids = mock(QualitySnapshotRetentionIdPort.class);
        QualitySnapshotRetentionAuthorityIngestPort ingest =
                mock(QualitySnapshotRetentionAuthorityIngestPort.class);
        QualitySnapshotRetentionExecutionPort executor =
                mock(QualitySnapshotRetentionExecutionPort.class);
        QualitySnapshotRetentionCandidate candidate = candidate();
        QualitySnapshotRetentionAuthorityEvidence evidence = evidence(AUTHORITY_ID);
        when(candidates.findNextDue()).thenReturn(Optional.of(candidate));
        when(ids.nextAttempt()).thenReturn(ATTEMPT);
        when(authority.verify(candidate, AUTHORITY_ID)).thenReturn(Optional.of(evidence));
        when(ingest.ingest(evidence)).thenReturn(AUTHORITY_ID);
        when(executor.execute(candidate, ATTEMPT, RETENTION_TRACE))
                .thenReturn(QualitySnapshotRetentionResult.COMPLETED);

        Optional<QualitySnapshotRetentionResult> result = orchestrator(
                        candidates, authority, ids, ingest, executor)
                .runOne(RETENTION_TRACE);

        assertEquals(Optional.of(QualitySnapshotRetentionResult.COMPLETED), result);
        InOrder order = inOrder(candidates, ids, authority, ingest, executor);
        order.verify(candidates).findNextDue();
        order.verify(ids).nextAttempt();
        order.verify(authority).verify(candidate, AUTHORITY_ID);
        order.verify(ingest).ingest(evidence);
        order.verify(executor).execute(candidate, ATTEMPT, RETENTION_TRACE);
        order.verifyNoMoreInteractions();
    }

    @Test
    void unavailableAuthorityUsesTheAttemptIdToMaterializeABlockedOwnerResult() {
        QualitySnapshotRetentionCandidatePort candidates =
                mock(QualitySnapshotRetentionCandidatePort.class);
        ConsumerRegistryAuthorityPort authority = mock(ConsumerRegistryAuthorityPort.class);
        QualitySnapshotRetentionIdPort ids = mock(QualitySnapshotRetentionIdPort.class);
        QualitySnapshotRetentionAuthorityIngestPort ingest =
                mock(QualitySnapshotRetentionAuthorityIngestPort.class);
        QualitySnapshotRetentionExecutionPort executor =
                mock(QualitySnapshotRetentionExecutionPort.class);
        QualitySnapshotRetentionCandidate candidate = candidate();
        when(candidates.findNextDue()).thenReturn(Optional.of(candidate));
        when(ids.nextAttempt()).thenReturn(ATTEMPT);
        when(authority.verify(candidate, AUTHORITY_ID)).thenReturn(Optional.empty());
        when(executor.execute(candidate, ATTEMPT, RETENTION_TRACE))
                .thenReturn(QualitySnapshotRetentionResult.BLOCKED);

        Optional<QualitySnapshotRetentionResult> result = orchestrator(
                        candidates, authority, ids, ingest, executor)
                .runOne(RETENTION_TRACE);

        assertEquals(Optional.of(QualitySnapshotRetentionResult.BLOCKED), result);
        verifyNoInteractions(ingest);
        verify(executor).execute(candidate, ATTEMPT, RETENTION_TRACE);
    }

    @Test
    void authorityRuntimeFailureAlsoBecomesTheSameBlockedOwnerAttempt() {
        QualitySnapshotRetentionCandidatePort candidates =
                mock(QualitySnapshotRetentionCandidatePort.class);
        ConsumerRegistryAuthorityPort authority = mock(ConsumerRegistryAuthorityPort.class);
        QualitySnapshotRetentionIdPort ids = mock(QualitySnapshotRetentionIdPort.class);
        QualitySnapshotRetentionAuthorityIngestPort ingest =
                mock(QualitySnapshotRetentionAuthorityIngestPort.class);
        QualitySnapshotRetentionExecutionPort executor =
                mock(QualitySnapshotRetentionExecutionPort.class);
        QualitySnapshotRetentionCandidate candidate = candidate();
        when(candidates.findNextDue()).thenReturn(Optional.of(candidate));
        when(ids.nextAttempt()).thenReturn(ATTEMPT);
        when(authority.verify(candidate, AUTHORITY_ID))
                .thenThrow(new IllegalStateException("authority unavailable"));
        when(executor.execute(candidate, ATTEMPT, RETENTION_TRACE))
                .thenReturn(QualitySnapshotRetentionResult.BLOCKED);

        Optional<QualitySnapshotRetentionResult> result = orchestrator(
                        candidates, authority, ids, ingest, executor)
                .runOne(RETENTION_TRACE);

        assertEquals(Optional.of(QualitySnapshotRetentionResult.BLOCKED), result);
        verifyNoInteractions(ingest);
        verify(executor).execute(candidate, ATTEMPT, RETENTION_TRACE);
    }

    @Test
    void rejectedAuthorityIngestAlsoBecomesTheSameBlockedOwnerAttempt() {
        QualitySnapshotRetentionCandidatePort candidates =
                mock(QualitySnapshotRetentionCandidatePort.class);
        ConsumerRegistryAuthorityPort authority = mock(ConsumerRegistryAuthorityPort.class);
        QualitySnapshotRetentionIdPort ids = mock(QualitySnapshotRetentionIdPort.class);
        QualitySnapshotRetentionAuthorityIngestPort ingest =
                mock(QualitySnapshotRetentionAuthorityIngestPort.class);
        QualitySnapshotRetentionExecutionPort executor =
                mock(QualitySnapshotRetentionExecutionPort.class);
        QualitySnapshotRetentionCandidate candidate = candidate();
        QualitySnapshotRetentionAuthorityEvidence evidence = evidence(AUTHORITY_ID);
        when(candidates.findNextDue()).thenReturn(Optional.of(candidate));
        when(ids.nextAttempt()).thenReturn(ATTEMPT);
        when(authority.verify(candidate, AUTHORITY_ID)).thenReturn(Optional.of(evidence));
        when(ingest.ingest(evidence)).thenThrow(new IllegalArgumentException("invalid"));
        when(executor.execute(candidate, ATTEMPT, RETENTION_TRACE))
                .thenReturn(QualitySnapshotRetentionResult.BLOCKED);

        Optional<QualitySnapshotRetentionResult> result = orchestrator(
                        candidates, authority, ids, ingest, executor)
                .runOne(RETENTION_TRACE);

        assertEquals(Optional.of(QualitySnapshotRetentionResult.BLOCKED), result);
        verify(executor).execute(candidate, ATTEMPT, RETENTION_TRACE);
    }

    @Test
    void noDueCandidateDoesNotCallTheExternalAuthorityOrPretendToClaim() {
        QualitySnapshotRetentionCandidatePort candidates =
                mock(QualitySnapshotRetentionCandidatePort.class);
        ConsumerRegistryAuthorityPort authority = mock(ConsumerRegistryAuthorityPort.class);
        QualitySnapshotRetentionIdPort ids = mock(QualitySnapshotRetentionIdPort.class);
        QualitySnapshotRetentionAuthorityIngestPort ingest =
                mock(QualitySnapshotRetentionAuthorityIngestPort.class);
        QualitySnapshotRetentionExecutionPort executor =
                mock(QualitySnapshotRetentionExecutionPort.class);
        when(candidates.findNextDue()).thenReturn(Optional.empty());

        Optional<QualitySnapshotRetentionResult> result = orchestrator(
                        candidates, authority, ids, ingest, executor)
                .runOne(RETENTION_TRACE);

        assertTrue(result.isEmpty());
        verify(candidates).findNextDue();
        verifyNoInteractions(authority, ids, ingest, executor);
        assertEquals("findNextDue", QualitySnapshotRetentionCandidatePort.class
                .getDeclaredMethods()[0]
                .getName());
    }

    @Test
    void invalidAuthorityIdentityFallsBackToANonDestructiveBlockedOwnerResult() {
        QualitySnapshotRetentionCandidatePort candidates =
                mock(QualitySnapshotRetentionCandidatePort.class);
        ConsumerRegistryAuthorityPort authority = mock(ConsumerRegistryAuthorityPort.class);
        QualitySnapshotRetentionIdPort ids = mock(QualitySnapshotRetentionIdPort.class);
        QualitySnapshotRetentionAuthorityIngestPort ingest =
                mock(QualitySnapshotRetentionAuthorityIngestPort.class);
        QualitySnapshotRetentionExecutionPort executor =
                mock(QualitySnapshotRetentionExecutionPort.class);
        QualitySnapshotRetentionCandidate candidate = candidate();
        UUID forged = UUID.fromString("019d2c7d-4000-7000-8000-000000000299");
        when(candidates.findNextDue()).thenReturn(Optional.of(candidate));
        when(ids.nextAttempt()).thenReturn(ATTEMPT);
        when(authority.verify(candidate, AUTHORITY_ID))
                .thenReturn(Optional.of(evidence(forged)));
        when(executor.execute(candidate, ATTEMPT, RETENTION_TRACE))
                .thenReturn(QualitySnapshotRetentionResult.BLOCKED);

        Optional<QualitySnapshotRetentionResult> result = orchestrator(
                        candidates, authority, ids, ingest, executor)
                .runOne(RETENTION_TRACE);

        assertEquals(Optional.of(QualitySnapshotRetentionResult.BLOCKED), result);
        verifyNoInteractions(ingest);
        verify(executor).execute(candidate, ATTEMPT, RETENTION_TRACE);
    }

    @Test
    void rejectsInvalidInvocationTraceBeforeReadingTheCandidate() {
        QualitySnapshotRetentionCandidatePort candidates =
                mock(QualitySnapshotRetentionCandidatePort.class);
        ConsumerRegistryAuthorityPort authority = mock(ConsumerRegistryAuthorityPort.class);
        QualitySnapshotRetentionIdPort ids = mock(QualitySnapshotRetentionIdPort.class);
        QualitySnapshotRetentionAuthorityIngestPort ingest =
                mock(QualitySnapshotRetentionAuthorityIngestPort.class);
        QualitySnapshotRetentionExecutionPort executor =
                mock(QualitySnapshotRetentionExecutionPort.class);
        QualitySnapshotRetentionOrchestrator orchestrator =
                orchestrator(candidates, authority, ids, ingest, executor);

        for (String invalid : Arrays.asList(
                null,
                "0".repeat(32),
                "A".repeat(32),
                "1".repeat(31),
                "1".repeat(33))) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> orchestrator.runOne(invalid));
            assertEquals("INGESTION_QUALITY_RETENTION_TRACE_INVALID", failure.getMessage());
        }
        verifyNoInteractions(candidates, authority, ids, ingest, executor);
    }

    @Test
    void externalAuthorityPortIsRequiredAndHasNoDefaultOrOwnerResultMethod() {
        assertThrows(
                NullPointerException.class,
                () -> new QualitySnapshotRetentionOrchestrator(
                        mock(QualitySnapshotRetentionCandidatePort.class),
                        null,
                        mock(QualitySnapshotRetentionIdPort.class),
                        mock(QualitySnapshotRetentionAuthorityIngestPort.class),
                        mock(QualitySnapshotRetentionExecutionPort.class)));
        assertEquals(1, ConsumerRegistryAuthorityPort.class.getDeclaredMethods().length);
        assertFalse(ConsumerRegistryAuthorityPort.class.getDeclaredMethods()[0].isDefault());
        assertEquals(2, ConsumerRegistryAuthorityPort.class.getDeclaredMethods()[0]
                .getParameterCount());
    }

    private static QualitySnapshotRetentionOrchestrator orchestrator(
            QualitySnapshotRetentionCandidatePort candidates,
            ConsumerRegistryAuthorityPort authority,
            QualitySnapshotRetentionIdPort ids,
            QualitySnapshotRetentionAuthorityIngestPort ingest,
            QualitySnapshotRetentionExecutionPort executor) {
        return new QualitySnapshotRetentionOrchestrator(
                candidates, authority, ids, ingest, executor);
    }

    private static QualitySnapshotRetentionCandidate candidate() {
        var scope = new QualitySnapshotRetentionScopeCanonicalizer.Material(
                "QualitySnapshot",
                UUID.fromString("019d2c7d-4000-7000-8000-000000000110"),
                "SRC-P0-STUDENT-001",
                9,
                Instant.parse("2026-08-10T00:00:00Z"),
                Instant.parse("2028-08-10T00:00:00Z"),
                "sha256:" + "b".repeat(64),
                "QUALITY-SNAPSHOT-RETENTION-1.0.0",
                "RS-1.0.0");
        return new QualitySnapshotRetentionCandidate(
                EXECUTION_ID,
                scope,
                QualitySnapshotRetentionScopeCanonicalizer.digest(scope));
    }

    private static QualitySnapshotRetentionAuthorityEvidence evidence(UUID evidenceId) {
        return new QualitySnapshotRetentionAuthorityEvidence(
                evidenceId,
                "{\"authority\":true}".getBytes(StandardCharsets.UTF_8),
                "a".repeat(64));
    }
}
