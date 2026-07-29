package cn.edu.suda.scholarsense.identityaccess.application;

import cn.edu.suda.scholarsense.identityaccess.domain.OrganizationGraph;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Applies one already authenticated and normalized batch without ever skipping a bad record. */
public final class IdentitySyncService {
    private final IdentitySyncRepository repository;
    private final IdentityReplayPort replay;
    private final IdentitySyncTransactionPort transactions;
    private final IdentitySyncAuditPort audit;
    private final IdentitySyncObservabilityPort observability;
    private final AuthorizationEffectivenessProbePort currentContexts;
    private final IdentitySloEvidencePort sloEvidence;
    private final TrustedTimeSource time;

    public IdentitySyncService(
            IdentitySyncRepository repository,
            IdentityReplayPort replay,
            IdentitySyncTransactionPort transactions,
            IdentitySyncAuditPort audit,
            IdentitySyncObservabilityPort observability,
            AuthorizationEffectivenessProbePort currentContexts,
            IdentitySloEvidencePort sloEvidence,
            TrustedTimeSource time) {
        this.repository = repository;
        this.replay = replay;
        this.transactions = transactions;
        this.audit = audit;
        this.observability = observability;
        this.currentContexts = currentContexts;
        this.sloEvidence = sloEvidence;
        this.time = time;
    }

    public IdentitySyncResult process(NormalizedIdentityBatch batch, IdentityLease lease) {
        return process(batch, lease, ignored -> {});
    }

    public IdentitySyncResult process(
            NormalizedIdentityBatch batch,
            IdentityLease lease,
            java.util.function.Consumer<IdentitySyncResult> afterApply) {
        Instant now = time.now().instant();
        validate(batch, lease, now);
        IdentityCheckpoint checkpoint = repository.checkpoint(batch.key())
                .orElseGet(() -> IdentityCheckpoint.initial(batch.key()));
        var existing = repository.envelopeDigest(batch.batchId());
        if (existing.isPresent()) {
            if (!existing.get().equals(batch.envelopeDigest())) {
                reject(batch, lease, now, "IDENTITY_SOURCE_PAYLOAD_CONFLICT", false);
                throw new IdentitySyncException("IDENTITY_SOURCE_PAYLOAD_CONFLICT");
            }
            IdentitySyncResult replayed = new IdentitySyncResult(
                    IdentitySyncOutcome.REPLAYED,
                    "IDENTITY_SYNC_REPLAYED",
                    checkpoint.watermark(),
                    checkpoint.sourceVersion(),
                    checkpoint.aggregateVersion());
            transactions.execute(() -> {
                afterApply.accept(replayed);
                return null;
            });
            return replayed;
        }
        requireContinuous(batch, lease, checkpoint, now);
        long nextAggregateVersion = checkpoint.aggregateVersion() + 1;
        IdentitySyncResult applied = new IdentitySyncResult(
                IdentitySyncOutcome.APPLIED,
                "IDENTITY_SYNC_APPLIED",
                batch.toWatermark(),
                batch.sourceVersion(),
                nextAggregateVersion);
        transactions.execute(() -> {
            repository.apply(batch, lease, now);
            audit.append(new IdentitySyncAuditEvent(
                    "identity.sync.applied",
                    "accepted",
                    "IDENTITY_SYNC_APPLIED",
                    lease.jobId(),
                    lease.attemptNo(),
                    lease.fencingToken(),
                    batch.sourceVersion(),
                    batch.toWatermark(),
                    nextAggregateVersion,
                    batch.traceId(),
                    now,
                    policyVersions(batch)));
            afterApply.accept(applied);
            return null;
        });
        observability.record(new IdentitySyncObservation(
                "identity_sync_applied_total",
                1,
                Map.of(
                        "sourceId", batch.key().sourceId(),
                        "feedId", batch.key().feedId(),
                        "consumerProjection", batch.key().consumerProjection(),
                        "outcome", "applied"),
                batch.traceId(),
                now));
        recordReadBackSlo(batch, now, nextAggregateVersion);
        return applied;
    }

    private void validate(NormalizedIdentityBatch batch, IdentityLease lease, Instant now) {
        if (!batch.key().equals(lease.key())
                || !lease.expiresAt().isAfter(now)
                || !repository.leaseIsCurrent(lease)) {
            throw new IdentitySyncException("IDENTITY_SYNC_FENCING_STALE");
        }
        if (!batch.signatureVerified()) {
            reject(batch, lease, now, "IDENTITY_SOURCE_SIGNATURE_INVALID", false);
            throw new IdentitySyncException("IDENTITY_SOURCE_SIGNATURE_INVALID");
        }
        for (IdentitySourceFact fact : batch.sourceFacts()) {
            repository.currentRecord(
                            batch.key(),
                            fact.recordKind(),
                            fact.externalRefDigest())
                    .ifPresent(current -> {
                        if (fact.sourceVersion() == current.sourceVersion()
                                && !fact.payloadDigest().equals(
                                        current.payloadDigest())) {
                            reject(
                                    batch,
                                    lease,
                                    now,
                                    "IDENTITY_SOURCE_PAYLOAD_CONFLICT",
                                    false);
                            throw new IdentitySyncException(
                                    "IDENTITY_SOURCE_PAYLOAD_CONFLICT");
                        }
                        if (fact.sourceVersion() <= current.sourceVersion()) {
                            reject(
                                    batch,
                                    lease,
                                    now,
                                    "IDENTITY_SOURCE_VERSION_STALE",
                                    true);
                            throw new IdentitySyncException(
                                    "IDENTITY_SOURCE_VERSION_STALE");
                        }
                    });
        }
        for (var account : batch.accounts()) {
            repository.currentSubjectBinding(
                            batch.key(), account.externalRefDigest())
                    .filter(current -> !current.equals(
                                    account.subjectBindingToken())
                            && !account.subjectBindingReadTokens().contains(current))
                    .ifPresent(current -> {
                        reject(
                                batch,
                                lease,
                                now,
                                "IDENTITY_SUBJECT_REBIND_FORBIDDEN",
                                false);
                        throw new IdentitySyncException(
                                "IDENTITY_SUBJECT_REBIND_FORBIDDEN");
                    });
        }
        try {
            Map<String, cn.edu.suda.scholarsense.identityaccess.domain.OrganizationNode>
                    organizationsByRef = new LinkedHashMap<>();
            repository.currentOrganizations(batch.key()).forEach(organization ->
                    organizationsByRef.put(
                            organization.externalRefDigest(), organization));
            batch.organizations().forEach(organization ->
                    organizationsByRef.put(
                            organization.externalRefDigest(), organization));
            OrganizationGraph.validate(List.copyOf(organizationsByRef.values()));
        } catch (IdentityAccessException invalidOrganization) {
            reject(batch, lease, now, invalidOrganization.code(), false);
            throw new IdentitySyncException(invalidOrganization.code());
        }
        Set<UUID> accounts = new HashSet<>();
        batch.accounts().forEach(account -> accounts.add(account.accountId()));
        Set<String> accountExternalRefs = new HashSet<>();
        batch.accounts().forEach(account ->
                accountExternalRefs.add(account.externalRefDigest()));
        Set<String> subjectBindings = new HashSet<>();
        batch.accounts().forEach(account ->
                subjectBindings.addAll(account.subjectBindingReadTokens()));
        Set<UUID> organizations = new HashSet<>();
        batch.organizations().forEach(org -> organizations.add(org.organizationId()));
        Set<UUID> bindings = new HashSet<>();
        batch.roleBindings().forEach(binding -> bindings.add(binding.bindingId()));
        Set<String> bindingExternalRefs = new HashSet<>();
        batch.roleBindings().forEach(binding ->
                bindingExternalRefs.add(binding.externalRefDigest()));
        long subjectBindingCount = batch.accounts().stream()
                .mapToLong(account -> account.subjectBindingReadTokens().size())
                .sum();
        if (subjectBindings.size() != subjectBindingCount) {
            reject(batch, lease, now, "IDENTITY_SUBJECT_BINDING_CONFLICT", false);
            throw new IdentitySyncException("IDENTITY_SUBJECT_BINDING_CONFLICT");
        }
        if (accountExternalRefs.size() != batch.accounts().size()
                || bindings.size() != batch.roleBindings().size()
                || bindingExternalRefs.size() != batch.roleBindings().size()) {
            reject(batch, lease, now, "IDENTITY_EXTERNAL_ID_DUPLICATE", false);
            throw new IdentitySyncException("IDENTITY_EXTERNAL_ID_DUPLICATE");
        }
        if (accounts.size() != batch.accounts().size()
                || organizations.size() != batch.organizations().size()
                || batch.roleBindings().stream().anyMatch(binding ->
                        (!accounts.contains(binding.accountId())
                                && !repository.currentAccountExists(
                                        batch.key(), binding.accountId()))
                                || (!organizations.contains(binding.organizationId())
                                        && !repository.currentOrganizationExists(
                                                batch.key(), binding.organizationId())))) {
            reject(batch, lease, now, "IDENTITY_BINDING_REFERENCE_INVALID", false);
            throw new IdentitySyncException("IDENTITY_BINDING_REFERENCE_INVALID");
        }
        Set<UUID> events = new HashSet<>();
        if (batch.sourceFacts().stream().anyMatch(fact -> !events.add(fact.eventId()))) {
            reject(batch, lease, now, "IDENTITY_SOURCE_EVENT_DUPLICATE", false);
            throw new IdentitySyncException("IDENTITY_SOURCE_EVENT_DUPLICATE");
        }
    }

    private void requireContinuous(
            NormalizedIdentityBatch batch,
            IdentityLease lease,
            IdentityCheckpoint checkpoint,
            Instant now) {
        if (batch.fromWatermark() > checkpoint.watermark()) {
            reject(
                    batch,
                    lease,
                    now,
                    "IDENTITY_SOURCE_CURSOR_GAP",
                    true,
                    () -> replay.request(
                            batch.key(),
                            checkpoint.watermark() + 1,
                            batch.fromWatermark(),
                            batch.traceId()));
            throw new IdentitySyncException("IDENTITY_SOURCE_CURSOR_GAP");
        }
        if (batch.fromWatermark() < checkpoint.watermark()
                || batch.sourceVersion() <= checkpoint.sourceVersion()) {
            reject(batch, lease, now, "IDENTITY_SOURCE_VERSION_STALE", true);
            throw new IdentitySyncException("IDENTITY_SOURCE_VERSION_STALE");
        }
    }

    private void reject(
            NormalizedIdentityBatch batch,
            IdentityLease lease,
            Instant now,
            String reasonCode,
            boolean replayable) {
        reject(batch, lease, now, reasonCode, replayable, () -> {});
    }

    private void reject(
            NormalizedIdentityBatch batch,
            IdentityLease lease,
            Instant now,
            String reasonCode,
            boolean replayable,
            Runnable beforeReject) {
        transactions.execute(() -> {
            if (!repository.leaseIsCurrent(lease)) {
                throw new IdentitySyncException("IDENTITY_SYNC_FENCING_STALE");
            }
            beforeReject.run();
            repository.reject(new IdentitySyncRejection(
                    UUID.fromString(UuidV7.generate(now)),
                    batch.batchId(),
                    batch.key(),
                    batch.sourceVersion(),
                    batch.toWatermark(),
                    batch.envelopeDigest(),
                    reasonCode,
                    replayable,
                    lease.jobId(),
                    lease.attemptNo(),
                    batch.traceId(),
                    now));
            audit.append(new IdentitySyncAuditEvent(
                    "identity.sync.rejected",
                    "rejected",
                    reasonCode,
                    lease.jobId(),
                    lease.attemptNo(),
                    lease.fencingToken(),
                    batch.sourceVersion(),
                    batch.toWatermark(),
                    0,
                    batch.traceId(),
                    now,
                    policyVersions(batch)));
            return null;
        });
    }

    private void recordReadBackSlo(
            NormalizedIdentityBatch batch, Instant appliedAt, long aggregateVersion) {
        for (IdentitySourceFact fact : batch.sourceFacts()) {
            List<AuthorizationEffectiveContext> readBack =
                    readBack(batch, fact);
            Instant effectiveAt = time.now().instant();
            if (readBack.isEmpty()) {
                persistSloEvidence(
                        batch,
                        appliedAt,
                        aggregateVersion,
                        fact,
                        null,
                        effectiveAt);
                continue;
            }
            for (AuthorizationEffectiveContext context : readBack) {
                persistSloEvidence(
                        batch,
                        appliedAt,
                        aggregateVersion,
                        fact,
                        context,
                        effectiveAt);
            }
        }
    }

    private void persistSloEvidence(
            NormalizedIdentityBatch batch,
            Instant appliedAt,
            long aggregateVersion,
            IdentitySourceFact fact,
            AuthorizationEffectiveContext readBack,
            Instant effectiveAt) {
            boolean versionVisible = readBack != null
                    && readBack.sourceVersion() == batch.sourceVersion()
                    && readBack.watermark() == batch.toWatermark();
            boolean within = versionVisible
                    && !effectiveAt.isAfter(
                            batch.sourceVisibleAt().plus(Duration.ofMinutes(15)));
            String reason = readBack == null
                    ? "IDENTITY_AUTHORIZATION_READBACK_EMPTY"
                    : !versionVisible
                            ? "IDENTITY_AUTHORIZATION_VERSION_MISMATCH"
                            : within
                                    ? null
                                    : "IDENTITY_AUTHORIZATION_EFFECTIVE_LATE";
            IdentitySloEvidence evidence = new IdentitySloEvidence(
                    UUID.fromString(UuidV7.generate(effectiveAt)),
                    readBack == null ? null : readBack.accountId(),
                    fact.recordKind(),
                    batch.sourceVersion(),
                    batch.toWatermark(),
                    aggregateVersion,
                    batch.sourceVisibleAt(),
                    appliedAt,
                    effectiveAt,
                    within,
                    reason,
                    batch.traceId());
            try {
                sloEvidence.append(evidence);
            } catch (RuntimeException unavailable) {
                try {
                    sloEvidence.compensate(
                            evidence, "IDENTITY_SLO_EVIDENCE_WRITE_FAILED");
                } catch (RuntimeException compensationUnavailable) {
                    observability.record(new IdentitySyncObservation(
                            "identity_sync_slo_probe_total",
                            1,
                            Map.of(
                                    "sourceId", batch.key().sourceId(),
                                    "feedId", batch.key().feedId(),
                                    "consumerProjection",
                                    batch.key().consumerProjection(),
                                    "outcome", "compensation_failed"),
                            batch.traceId(),
                            effectiveAt));
                }
                observability.record(new IdentitySyncObservation(
                        "identity_sync_slo_probe_total",
                        1,
                        Map.of(
                                "sourceId", batch.key().sourceId(),
                                "feedId", batch.key().feedId(),
                                "consumerProjection", batch.key().consumerProjection(),
                                "outcome", "compensation_required"),
                        batch.traceId(),
                        effectiveAt));
            }
    }

    private List<AuthorizationEffectiveContext> readBack(
            NormalizedIdentityBatch batch, IdentitySourceFact fact) {
        java.util.stream.Stream<String> candidates;
        if (fact.recordKind() == IdentityRecordKind.ACCOUNT) {
            candidates = batch.accounts().stream()
                    .filter(account -> account.externalRefDigest().equals(
                            fact.externalRefDigest()))
                    .flatMap(account -> account.subjectBindingReadTokens().stream());
        } else if (fact.recordKind() == IdentityRecordKind.EMPLOYMENT_ROLE) {
            java.util.List<UUID> accountIds = batch.roleBindings().stream()
                    .filter(binding -> binding.externalRefDigest().equals(
                            fact.externalRefDigest()))
                    .map(binding -> binding.accountId())
                    .toList();
            candidates = accountIds.stream()
                    .flatMap(accountId -> java.util.stream.Stream.concat(
                            batch.accounts().stream()
                                    .filter(account -> account.accountId().equals(accountId))
                                    .flatMap(account ->
                                            account.subjectBindingReadTokens().stream()),
                            repository.currentSubjectBinding(batch.key(), accountId)
                                    .stream()));
        } else if (fact.recordKind() == IdentityRecordKind.ORGANIZATION) {
            candidates = batch.organizations().stream()
                    .filter(organization -> organization.externalRefDigest().equals(
                            fact.externalRefDigest()))
                    .flatMap(organization -> java.util.stream.Stream.concat(
                            batch.roleBindings().stream()
                                    .filter(binding -> binding.organizationId().equals(
                                            organization.organizationId()))
                                    .flatMap(binding -> batch.accounts().stream()
                                            .filter(account -> account.accountId().equals(
                                                    binding.accountId()))
                                            .flatMap(account ->
                                                    account.subjectBindingReadTokens().stream())),
                            repository.currentSubjectBindingsForOrganization(
                                            batch.key(), organization.organizationId())
                                    .stream()))
                    ;
        } else {
            return List.of();
        }
        Map<UUID, AuthorizationEffectiveContext> contexts = new LinkedHashMap<>();
        candidates.distinct()
                .map(currentContexts::findCurrent)
                .flatMap(java.util.Optional::stream)
                .forEach(context ->
                        contexts.putIfAbsent(context.accountId(), context));
        return List.copyOf(contexts.values());
    }

    private static Map<String, String> policyVersions(NormalizedIdentityBatch batch) {
        return Map.of(
                "identitySessionPolicy", "ISP-1.0.0",
                "roleFieldPolicy", "RFP-1.0.0",
                "roleMapping", batch.mappingVersion(),
                "retentionSchedule", "RS-1.0.0");
    }
}
