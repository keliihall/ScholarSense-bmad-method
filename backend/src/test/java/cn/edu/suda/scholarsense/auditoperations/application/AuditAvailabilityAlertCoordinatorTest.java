package cn.edu.suda.scholarsense.auditoperations.application;

import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.EVENT_ID;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.NOW;
import static cn.edu.suda.scholarsense.auditoperations.AuditLedgerTestFixtures.TRACE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.edu.suda.scholarsense.auditoperations.domain.AuditAvailability;
import cn.edu.suda.scholarsense.auditoperations.domain.AvailabilityState;
import cn.edu.suda.scholarsense.auditoperations.domain.FindingCode;
import cn.edu.suda.scholarsense.auditoperations.domain.IntegrityFinding;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuditAvailabilityAlertCoordinatorTest {

    @Test
    void emitsOneActiveTransitionAndOneResolvedEventWithoutSensitiveMeasurementData() {
        var findings = new ArrayList<IntegrityFinding>();
        var alerts = new FakeAlerts();
        var coordinator = new AuditAvailabilityAlertCoordinator(
                new FindingRepository() {
                    @Override public void save(IntegrityFinding finding) { findings.add(finding); }
                    @Override public boolean hasActivePermanentFinding() { return false; }
                    @Override public List<IntegrityFinding> activeFindings() {
                        return findings.stream()
                                .filter(finding -> !alerts.resolved.contains(finding))
                                .toList();
                    }
                },
                alerts,
                ignored -> EVENT_ID);
        var measurement = new AuditBacklogMeasurement(10_000, 59, false, true, NOW, true);
        var degraded = availability(AvailabilityState.DEGRADED, Set.of(FindingCode.AUDIT_INGESTION_BACKLOG));

        coordinator.observe(degraded, measurement);
        coordinator.observe(degraded, measurement);
        coordinator.observe(availability(AvailabilityState.HEALTHY, Set.of()), measurement);
        coordinator.observe(availability(AvailabilityState.HEALTHY, Set.of()), measurement);

        assertEquals(1, findings.size());
        assertEquals(1, alerts.active.size());
        assertEquals(1, alerts.resolved.size());
        assertEquals(NOW.plusSeconds(15), alerts.resolvedAt);
        String evidence = findings.getFirst().toString().toLowerCase();
        for (String forbidden : List.of("student", "token", "cookie", "payload", "actor", "192.0.2")) {
            assertEquals(false, evidence.contains(forbidden));
        }
    }

    @Test
    void restartFindsThePersistedActiveFindingAndEmitsTheResolvedEvent() {
        var findings = new ArrayList<IntegrityFinding>();
        var alerts = new FakeAlerts();
        FindingRepository repository = new FindingRepository() {
            @Override public void save(IntegrityFinding finding) { findings.add(finding); }
            @Override public boolean hasActivePermanentFinding() { return false; }
            @Override public List<IntegrityFinding> activeFindings() {
                return findings.stream()
                        .filter(finding -> !alerts.resolved.contains(finding))
                        .toList();
            }
        };
        var measurement = new AuditBacklogMeasurement(10_000, 59, false, true, NOW, true);
        new AuditAvailabilityAlertCoordinator(repository, alerts, ignored -> EVENT_ID)
                .observe(availability(
                        AvailabilityState.DEGRADED,
                        Set.of(FindingCode.AUDIT_INGESTION_BACKLOG)), measurement);

        new AuditAvailabilityAlertCoordinator(repository, alerts, ignored -> EVENT_ID)
                .observe(availability(AvailabilityState.HEALTHY, Set.of()), measurement);

        assertEquals(1, alerts.resolved.size());
    }

    @Test
    void rolledBackResolvedWriteIsRetriedInsteadOfBeingSuppressedByJvmState() {
        var findings = new ArrayList<IntegrityFinding>();
        var alerts = new FakeAlerts();
        FindingRepository repository = new FindingRepository() {
            @Override public void save(IntegrityFinding finding) { findings.add(finding); }
            @Override public boolean hasActivePermanentFinding() { return false; }
            @Override public List<IntegrityFinding> activeFindings() {
                return findings.stream()
                        .filter(finding -> !alerts.resolved.contains(finding))
                        .toList();
            }
        };
        var coordinator = new AuditAvailabilityAlertCoordinator(repository, alerts, ignored -> EVENT_ID);
        var measurement = new AuditBacklogMeasurement(10_000, 59, false, true, NOW, true);
        coordinator.observe(
                availability(AvailabilityState.DEGRADED,
                        Set.of(FindingCode.AUDIT_INGESTION_BACKLOG)),
                measurement);

        coordinator.observe(availability(AvailabilityState.HEALTHY, Set.of()), measurement);
        alerts.resolved.clear(); // Models the enclosing database transaction rolling back.
        coordinator.observe(availability(AvailabilityState.HEALTHY, Set.of()), measurement);

        assertEquals(1, alerts.resolved.size());
    }

    private static AuditAvailability availability(AvailabilityState state, Set<FindingCode> reasons) {
        return new AuditAvailability(
                state, "AUDIT-INGESTION-POLICY-1.0.0", reasons,
                NOW.plusSeconds(15), NOW.plusSeconds(60), TRACE_ID);
    }

    private static final class FakeAlerts implements AlertOutboxPort {
        private final List<IntegrityFinding> active = new ArrayList<>();
        private final List<IntegrityFinding> resolved = new ArrayList<>();
        private Instant resolvedAt;

        @Override public void enqueueActive(IntegrityFinding finding) { active.add(finding); }

        @Override public void enqueueResolved(IntegrityFinding finding, Instant instant) {
            resolved.add(finding);
            resolvedAt = instant;
        }
    }
}
