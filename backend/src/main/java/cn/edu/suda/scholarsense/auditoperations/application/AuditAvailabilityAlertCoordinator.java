package cn.edu.suda.scholarsense.auditoperations.application;

import cn.edu.suda.scholarsense.auditoperations.domain.AuditAvailability;
import cn.edu.suda.scholarsense.auditoperations.domain.AvailabilityState;
import cn.edu.suda.scholarsense.auditoperations.domain.FindingCode;
import cn.edu.suda.scholarsense.auditoperations.domain.FindingSeverity;
import cn.edu.suda.scholarsense.auditoperations.domain.IntegrityFinding;
import cn.edu.suda.scholarsense.auditoperations.domain.LedgerHash;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** Converts availability state transitions into privacy-safe, deduplicated alert evidence. */
public final class AuditAvailabilityAlertCoordinator implements AuditAvailabilityObserver {
    private final FindingRepository findings;
    private final AlertOutboxPort alerts;
    private final FindingIdPort identifiers;

    public AuditAvailabilityAlertCoordinator(
            FindingRepository findings, AlertOutboxPort alerts, FindingIdPort identifiers) {
        this.findings = Objects.requireNonNull(findings);
        this.alerts = Objects.requireNonNull(alerts);
        this.identifiers = Objects.requireNonNull(identifiers);
    }

    @Override
    public synchronized void observe(
            AuditAvailability availability, AuditBacklogMeasurement measurement) {
        IntegrityFinding activeFinding = findings.activeFindings().stream()
                .filter(finding -> finding.code() == FindingCode.AUDIT_INGESTION_BACKLOG)
                .reduce((first, second) -> second)
                .orElse(null);
        if (availability.state() == AvailabilityState.HEALTHY) {
            if (activeFinding != null) {
                alerts.enqueueResolved(activeFinding, availability.observedAt());
            }
        } else if (activeFinding == null) {
            IntegrityFinding created = finding(availability, measurement);
            findings.save(created);
            alerts.enqueueActive(created);
        }
    }

    private IntegrityFinding finding(
            AuditAvailability availability, AuditBacklogMeasurement measurement) {
        String digest = digest(measurement);
        Instant occurredAt = measurement.measuredAt().isAfter(availability.observedAt())
                ? availability.observedAt() : measurement.measuredAt();
        return new IntegrityFinding(
                identifiers.newId(availability.observedAt()),
                FindingCode.AUDIT_INGESTION_BACKLOG,
                availability.state() == AvailabilityState.BLOCKED
                        ? FindingSeverity.CRITICAL : FindingSeverity.WARNING,
                availability.policyVersion(),
                "AUDIT-LEDGER-HASH-1.0.0",
                null,
                null,
                "identity-access:" + digest.substring(0, 16),
                new LedgerHash(digest),
                availability.traceId(),
                occurredAt,
                availability.observedAt(),
                "runbook://audit/ingestion-backlog");
    }

    private static String digest(AuditBacklogMeasurement measurement) {
        String safeMaterial = measurement.unconfirmedCount() + "\0"
                + measurement.oldestUnconfirmedAgeSeconds() + "\0"
                + measurement.permanentFindingActive() + "\0"
                + measurement.chainHealthy() + "\0"
                + measurement.measuredAt() + "\0" + measurement.available();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(safeMaterial.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
