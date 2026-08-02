package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.util.Objects;
import java.time.Duration;
import java.time.ZoneId;
import java.util.UUID;

/** Records V2 reconciliation evidence and performs the gated live-projection switch. */
public final class ResponsibilityV2CutoverService {
    private static final String V2_CONTRACT =
            "RESPONSIBILITY-AUTHORITY-2.0.0";
    private static final String AUTHORIZED_PROFILE_DIGEST =
            "198e82c1b1cc29cb723e67593166afc9372b79f62c93c659962ed24788e96342";
    private static final Duration COMMAND_AGE = Duration.ofMinutes(5);
    private static final ZoneId COMMAND_ZONE =
            ZoneId.of("Asia/Shanghai");
    private final ResponsibilitySyncRepository repository;
    private final IdentitySyncTransactionPort transactions;
    private final TrustedTimeSource time;
    private final AccessInvalidationStorePort invalidations;
    private final AccessInvalidationEventCodecPort events;
    private final AccessInvalidationIdPort identifiers;
    private final AccessInvalidationAuditPort audit;
    private final AccessInvalidationExpiryPort expiryJobs;
    private final ResponsibilityFullSnapshotSourcePort snapshots;
    private final IdentitySyncAuditPort syncAudit;
    private final String authorizedProfileDigest;
    private final ResponsibilityV2CutoverCommandSignaturePort signatures;

    public ResponsibilityV2CutoverService(
            ResponsibilitySyncRepository repository,
            IdentitySyncTransactionPort transactions,
            TrustedTimeSource time,
            AccessInvalidationStorePort invalidations,
            AccessInvalidationEventCodecPort events,
            AccessInvalidationIdPort identifiers,
            AccessInvalidationAuditPort audit,
            AccessInvalidationExpiryPort expiryJobs) {
        this(
                repository,
                transactions,
                time,
                invalidations,
                events,
                identifiers,
                audit,
                expiryJobs,
                (key, businessDate, traceId) -> {
                    throw new IdentitySyncException(
                            "RESPONSIBILITY_V2_RECONCILIATION_SNAPSHOT_UNAVAILABLE");
                },
                ignored -> {},
                AUTHORIZED_PROFILE_DIGEST,
                ResponsibilityV2CutoverCommandSignaturePort.rejectAll());
    }

    public ResponsibilityV2CutoverService(
            ResponsibilitySyncRepository repository,
            IdentitySyncTransactionPort transactions,
            TrustedTimeSource time,
            AccessInvalidationStorePort invalidations,
            AccessInvalidationEventCodecPort events,
            AccessInvalidationIdPort identifiers,
            AccessInvalidationAuditPort audit,
            AccessInvalidationExpiryPort expiryJobs,
            ResponsibilityFullSnapshotSourcePort snapshots,
            IdentitySyncAuditPort syncAudit) {
        this(
                repository,
                transactions,
                time,
                invalidations,
                events,
                identifiers,
                audit,
                expiryJobs,
                snapshots,
                syncAudit,
                AUTHORIZED_PROFILE_DIGEST,
                ResponsibilityV2CutoverCommandSignaturePort.rejectAll());
    }

    public ResponsibilityV2CutoverService(
            ResponsibilitySyncRepository repository,
            IdentitySyncTransactionPort transactions,
            TrustedTimeSource time,
            AccessInvalidationStorePort invalidations,
            AccessInvalidationEventCodecPort events,
            AccessInvalidationIdPort identifiers,
            AccessInvalidationAuditPort audit,
            AccessInvalidationExpiryPort expiryJobs,
            ResponsibilityFullSnapshotSourcePort snapshots,
            IdentitySyncAuditPort syncAudit,
            String authorizedProfileDigest) {
        this(
                repository,
                transactions,
                time,
                invalidations,
                events,
                identifiers,
                audit,
                expiryJobs,
                snapshots,
                syncAudit,
                authorizedProfileDigest,
                ResponsibilityV2CutoverCommandSignaturePort.rejectAll());
    }

    public ResponsibilityV2CutoverService(
            ResponsibilitySyncRepository repository,
            IdentitySyncTransactionPort transactions,
            TrustedTimeSource time,
            AccessInvalidationStorePort invalidations,
            AccessInvalidationEventCodecPort events,
            AccessInvalidationIdPort identifiers,
            AccessInvalidationAuditPort audit,
            AccessInvalidationExpiryPort expiryJobs,
            ResponsibilityFullSnapshotSourcePort snapshots,
            IdentitySyncAuditPort syncAudit,
            String authorizedProfileDigest,
            ResponsibilityV2CutoverCommandSignaturePort signatures) {
        this.repository = Objects.requireNonNull(repository);
        this.transactions = Objects.requireNonNull(transactions);
        this.time = Objects.requireNonNull(time);
        this.invalidations = Objects.requireNonNull(invalidations);
        this.events = Objects.requireNonNull(events);
        this.identifiers = Objects.requireNonNull(identifiers);
        this.audit = Objects.requireNonNull(audit);
        this.expiryJobs = Objects.requireNonNull(expiryJobs);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.syncAudit = Objects.requireNonNull(syncAudit);
        this.signatures = Objects.requireNonNull(signatures);
        if (authorizedProfileDigest == null
                || !authorizedProfileDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_V2_CUTOVER_PROFILE_INVALID");
        }
        this.authorizedProfileDigest = authorizedProfileDigest;
    }

    public IdentityCheckpoint execute(
            ResponsibilityV2CutoverCommand command) {
        Objects.requireNonNull(command, "command");
        var existing = repository.v2CutoverCommand(
                command.commandId());
        if (existing.isPresent()) {
            if (!command.canonicalDigest().equals(
                    existing.get().commandDigest())) {
                throw new IdentitySyncException(
                        "RESPONSIBILITY_V2_CUTOVER_COMMAND_CONFLICT");
            }
            if ("activated".equals(existing.get().status())) {
                return repository.checkpoint(command.key())
                        .orElseThrow(() -> new IdentitySyncException(
                                "RESPONSIBILITY_V2_CUTOVER_COMMAND_STATE_INVALID"));
            }
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_CUTOVER_COMMAND_DUPLICATE");
        }
        transactions.execute(() -> {
            repository.beginV2CutoverCommand(command);
            appendCommandAudit(
                    command,
                    "responsibility.v2.cutover.requested",
                    "accepted",
                    "RESPONSIBILITY_V2_CUTOVER_REQUESTED",
                    command.requestedAt());
            return null;
        });
        var now = time.now().instant();
        if (!authorized(command, now)) {
            finishCommand(
                    command,
                    "denied",
                    "RESPONSIBILITY_V2_CUTOVER_COMMAND_UNAUTHORIZED",
                    null,
                    now,
                    "responsibility.v2.cutover.denied",
                    "RESPONSIBILITY_V2_CUTOVER_DENIED");
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_CUTOVER_COMMAND_UNAUTHORIZED");
        }
        UUID snapshotId = null;
        try {
            ResponsibilityV2ReconciliationEvidence evidence =
                    recordReconciliation(new ResponsibilityV2ReconciliationRequest(
                            command.key(),
                            command.businessDate(),
                            command.traceId()));
            snapshotId = evidence.snapshotId();
            if (!evidence.matched()) {
                throw new IdentitySyncException(
                        "RESPONSIBILITY_V2_CUTOVER_GATE_BLOCKED");
            }
            IdentityCheckpoint activated = activate(
                    new ResponsibilityV2CutoverRequest(
                            command.key(),
                            snapshotId,
                            command.traceId()),
                    command.commandId());
            return activated;
        } catch (IdentitySyncException failure) {
            finishCommand(
                    command,
                    "failed",
                    failure.code(),
                    snapshotId,
                    time.now().instant(),
                    "responsibility.v2.cutover.failed",
                    "RESPONSIBILITY_V2_CUTOVER_FAILED");
            throw failure;
        } catch (RuntimeException unavailable) {
            String reason =
                    "RESPONSIBILITY_V2_CUTOVER_DEPENDENCY_UNAVAILABLE";
            finishCommand(
                    command,
                    "failed",
                    reason,
                    snapshotId,
                    time.now().instant(),
                    "responsibility.v2.cutover.failed",
                    "RESPONSIBILITY_V2_CUTOVER_FAILED");
            throw new IdentitySyncException(reason);
        }
    }

    ResponsibilityV2ReconciliationEvidence recordReconciliation(
            ResponsibilityV2ReconciliationRequest request) {
        ResponsibilityFullSnapshot snapshot = snapshots.fetchVersion(
                request.key(),
                request.businessDate(),
                V2_CONTRACT,
                request.traceId());
        if (!request.key().equals(snapshot.key())
                || !request.businessDate().equals(
                        snapshot.businessDate())
                || !request.traceId().equals(snapshot.traceId())
                || !V2_CONTRACT.equals(snapshot.contractVersion())
                || !snapshot.signatureVerified()) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_RECONCILIATION_SNAPSHOT_INVALID");
        }
        var reconciledAt = time.now().instant();
        return transactions.execute(() -> {
            ResponsibilityV2ReconciliationEvidence evidence =
                    repository.recordV2Reconciliation(
                            snapshot, reconciledAt);
            syncAudit.append(new IdentitySyncAuditEvent(
                    "responsibility.v2.reconciled",
                    "accepted",
                    evidence.matched()
                            ? "RESPONSIBILITY_V2_RECONCILIATION_MATCHED"
                            : "RESPONSIBILITY_V2_RECONCILIATION_DIFFERENCES",
                    evidence.snapshotId(),
                    1,
                    1,
                    evidence.snapshotSourceVersion(),
                    evidence.throughWatermark(),
                    evidence.throughWatermark(),
                    evidence.traceId(),
                    reconciledAt,
                    policyVersions(),
                    evidence.snapshotId(),
                    ResponsibilityV2CutoverCommand.CONTROLLED_OPERATOR));
            return evidence;
        });
    }

    IdentityCheckpoint reconcileAndActivate(
            ResponsibilityV2ReconciliationRequest request) {
        ResponsibilityV2ReconciliationEvidence evidence =
                recordReconciliation(request);
        if (!evidence.matched()) {
            throw new IdentitySyncException(
                    "RESPONSIBILITY_V2_CUTOVER_GATE_BLOCKED");
        }
        return activate(new ResponsibilityV2CutoverRequest(
                request.key(), evidence.snapshotId(), request.traceId()));
    }

    IdentityCheckpoint activate(
            ResponsibilityV2CutoverRequest request) {
        return activate(request, null);
    }

    private IdentityCheckpoint activate(
            ResponsibilityV2CutoverRequest request,
            UUID commandId) {
        return transactions.execute(() -> {
            var activatedAt = time.now().instant();
            var facts = repository.v2ShadowInvalidationFacts(request.key());
            for (var fact : facts) {
                long expectedVersion = fact.aggregateVersion() - 1;
                long expectedFence = invalidations.fencingToken(
                        fact.lineageId().value());
                invalidations.append(new AccessInvalidationAppendCommand(
                        fact,
                        identifiers.next(activatedAt),
                        expectedVersion,
                        expectedFence,
                        events.encode(fact),
                        activatedAt));
                audit.factAppended(fact);
            }
            for (var expiry : repository.v2ShadowExpiryCandidates(
                    request.key(), activatedAt)) {
                expiryJobs.enqueue(
                        identifiers.next(activatedAt),
                        expiry.lineageId(),
                        expiry.scheduledEventId(),
                        expiry.scheduledAggregateVersion(),
                        expiry.effectiveTo(),
                        cn.edu.suda.scholarsense.identityaccess.domain
                                .AccessInvalidationReason.RELATION_EXPIRED,
                        expiry.traceId(),
                        activatedAt);
            }
            repository.markV2InvalidationReplayMaterialized(
                    request, facts.size());
            IdentityCheckpoint activated = repository.activateV2Shadow(
                    request, activatedAt);
            syncAudit.append(new IdentitySyncAuditEvent(
                    "responsibility.v2.activated",
                    "accepted",
                    "RESPONSIBILITY_V2_CUTOVER_ACTIVATED",
                    request.reconciliationSnapshotId(),
                    1,
                    1,
                    activated.sourceVersion(),
                    activated.watermark(),
                    activated.aggregateVersion(),
                    request.traceId(),
                    activatedAt,
                    policyVersions(),
                    request.reconciliationSnapshotId(),
                    ResponsibilityV2CutoverCommand.CONTROLLED_OPERATOR));
            if (commandId != null) {
                repository.finishV2CutoverCommand(
                        commandId,
                        "activated",
                        "RESPONSIBILITY_V2_CUTOVER_ACTIVATED",
                        request.reconciliationSnapshotId(),
                        activatedAt);
            }
            return activated;
        });
    }

    private boolean authorized(
            ResponsibilityV2CutoverCommand command,
            java.time.Instant now) {
        return ResponsibilityV2CutoverCommand.CONTROLLED_OPERATOR.equals(
                        command.operatorRef())
                && ResponsibilityV2CutoverCommand.APPROVAL.equals(
                        command.approvalRef())
                && authorizedProfileDigest.equals(
                        command.profileDigest())
                && signatures.verify(
                        command.signingPayload(),
                        command.signatureDigest())
                && !command.requestedAt().isAfter(now.plusSeconds(30))
                && !command.requestedAt().isBefore(
                        now.minus(COMMAND_AGE))
                && command.businessDate().equals(
                        now.atZone(COMMAND_ZONE).toLocalDate());
    }

    private void finishCommand(
            ResponsibilityV2CutoverCommand command,
            String status,
            String storedReason,
            UUID snapshotId,
            java.time.Instant completedAt,
            String auditAction,
            String auditReason) {
        transactions.execute(() -> {
            repository.finishV2CutoverCommand(
                    command.commandId(),
                    status,
                    storedReason,
                    snapshotId,
                    completedAt);
            appendCommandAudit(
                    command,
                    auditAction,
                    "rejected",
                    auditReason,
                    completedAt);
            return null;
        });
    }

    private void appendCommandAudit(
            ResponsibilityV2CutoverCommand command,
            String action,
            String outcome,
            String reason,
            java.time.Instant occurredAt) {
        syncAudit.append(new IdentitySyncAuditEvent(
                action,
                outcome,
                reason,
                command.commandId(),
                1,
                1,
                0,
                0,
                0,
                command.traceId(),
                occurredAt,
                policyVersions(),
                command.commandId(),
                command.operatorRef()));
    }

    private static java.util.Map<String, String> policyVersions() {
        return java.util.Map.of(
                "identitySessionPolicy", "ISP-1.0.0",
                "roleFieldPolicy", "RFP-1.0.0",
                "responsibilityContract", V2_CONTRACT,
                "retentionSchedule", "RS-1.0.0");
    }
}
