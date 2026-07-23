package cn.edu.suda.scholarsense.auditoperations.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.edu.suda.scholarsense.auditoperations.domain.AuditSearchView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetentionExecutionReadServiceTest {
    @Test
    void authorizesProjectsAndAuditsBeforeReturning() {
        List<SearchAuditEvent> audits = new ArrayList<>();
        RetentionExecution execution = execution();
        RetentionExecutionReadService service = new RetentionExecutionReadService(
                ignored -> Optional.of(execution), request -> new AuthorizedAuditSearchDecision(
                        true, "RFP-1.0.0", "audit.search-technical-metadata", Set.of("audit-domain"),
                        technicalProjection(), null),
                audits::add, () -> Instant.parse("2026-07-23T00:00:01Z"));

        RetentionExecutionEvidenceView view = service.read(
                execution.executionId(), AuditSearchView.TECHNICAL, "r7", "a".repeat(32));

        assertEquals("4".repeat(64), view.fields().get("archiveDigest"));
        assertFalse(view.fields().containsKey("actor"));
        assertFalse(view.fields().containsKey("archiveObjectUrl"));
        assertEquals("accepted", audits.getFirst().outcome());
    }

    @Test
    void denialAndMissingAreSameShapeAndAreAuditedFirst() {
        List<SearchAuditEvent> audits = new ArrayList<>();
        RetentionExecutionReadService denied = new RetentionExecutionReadService(
                ignored -> Optional.of(execution()), request -> new AuthorizedAuditSearchDecision(
                        false, "RFP-1.0.0", "audit.search-business-metadata", Set.of(), Map.of(), "denied"),
                audits::add, () -> Instant.parse("2026-07-23T00:00:01Z"));
        RetentionExecutionReadService missing = new RetentionExecutionReadService(
                ignored -> Optional.empty(), request -> new AuthorizedAuditSearchDecision(
                        true, "RFP-1.0.0", "audit.search-business-metadata", Set.of("audit-domain"),
                        businessProjection(), null),
                audits::add, () -> Instant.parse("2026-07-23T00:00:01Z"));

        assertEquals("AUDIT_EVIDENCE_NOT_AVAILABLE", assertThrows(AuditSearchException.class,
                () -> denied.read(execution().executionId(), AuditSearchView.BUSINESS, "r3", "a".repeat(32))).code());
        assertEquals("AUDIT_EVIDENCE_NOT_AVAILABLE", assertThrows(AuditSearchException.class,
                () -> missing.read(execution().executionId(), AuditSearchView.BUSINESS, "r3", "a".repeat(32))).code());
        assertEquals(List.of("rejected", "rejected"), audits.stream().map(SearchAuditEvent::outcome).toList());
    }

    private static RetentionExecution execution() {
        Instant now = Instant.parse("2026-07-23T00:00:00Z");
        return new RetentionExecution(
                "AUDIT-RETENTION-EXECUTION-1.0.0", AuditUuidV7.generate(now), "RS-1.0.0",
                "audit-domain", "fixture-a", "a".repeat(64), 42, "b".repeat(64),
                RetentionExecutionState.SUCCEEDED, 1, 1, 3, "destroy-fixture", 42, 42,
                "4".repeat(64), "fixture-worker", now, "a".repeat(32), true, List.of(),
                List.of(new RetentionExecutionStep("persist-evidence", "succeeded", null)));
    }

    private static Map<String, AuditFieldVisibility> technicalProjection() {
        return Map.of(
                "B", AuditFieldVisibility.CLEAR,
                "I", AuditFieldVisibility.HIDDEN,
                "C", AuditFieldVisibility.HIDDEN,
                "S", AuditFieldVisibility.HIDDEN,
                "E", AuditFieldVisibility.HIDDEN,
                "N", AuditFieldVisibility.HIDDEN,
                "G", AuditFieldVisibility.MASKED,
                "T", AuditFieldVisibility.CLEAR);
    }

    private static Map<String, AuditFieldVisibility> businessProjection() {
        return Map.of(
                "B", AuditFieldVisibility.CLEAR,
                "I", AuditFieldVisibility.MASKED,
                "C", AuditFieldVisibility.HIDDEN,
                "S", AuditFieldVisibility.HIDDEN,
                "E", AuditFieldVisibility.HIDDEN,
                "N", AuditFieldVisibility.HIDDEN,
                "G", AuditFieldVisibility.CLEAR,
                "T", AuditFieldVisibility.MASKED);
    }
}
