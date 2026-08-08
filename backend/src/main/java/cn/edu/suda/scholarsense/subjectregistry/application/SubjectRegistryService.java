package cn.edu.suda.scholarsense.subjectregistry.application;

import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationDecision;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckOutcome;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckPort;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRecheckRequest;
import cn.edu.suda.scholarsense.identityaccess.api.CompositeAuthorizationRequest;
import cn.edu.suda.scholarsense.shared.time.AuditAvailabilityPort;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import cn.edu.suda.scholarsense.subjectregistry.domain.AuthorityEvidence;
import cn.edu.suda.scholarsense.subjectregistry.domain.CorrectionType;
import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierKey;
import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierType;
import cn.edu.suda.scholarsense.subjectregistry.domain.MappingCorrectionEvent;
import cn.edu.suda.scholarsense.subjectregistry.domain.MappingExceptionCode;
import cn.edu.suda.scholarsense.subjectregistry.domain.MappingExceptionStatus;
import cn.edu.suda.scholarsense.subjectregistry.domain.MappingResolutionCode;
import cn.edu.suda.scholarsense.subjectregistry.domain.ResolutionOutcome;
import cn.edu.suda.scholarsense.subjectregistry.domain.StudentRef;
import cn.edu.suda.scholarsense.subjectregistry.domain.StudentRefIssuanceDecision;
import cn.edu.suda.scholarsense.subjectregistry.domain.StudentRefIssuer;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMapping;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMappingException;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectRegistryException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class SubjectRegistryService {
    private static final String FORBIDDEN = "SUBJECT_REGISTRY_FORBIDDEN";
    private static final String DEPENDENCY_UNAVAILABLE = "SUBJECT_REGISTRY_DEPENDENCY_UNAVAILABLE";
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofDays(90);
    private static final int MAX_SCAN_BATCH = 101;

    private final SubjectRegistryRepository repository;
    private final IdentifierProtectionPort protection;
    private final SourceIdentifierPolicyPort sourcePolicy;
    private final SubjectRegistryIdempotencyPort idempotency;
    private final CompositeAuthorizationPort authorization;
    private final CompositeAuthorizationRecheckPort authorizationRecheck;
    private final SubjectRegistryTransactionPort transactions;
    private final SubjectRegistryAuditPort audit;
    private final SubjectRegistryOutboxPort outbox;
    private final AuditAvailabilityPort auditAvailability;
    private final TrustedTimeSource time;
    private final SubjectRegistryIdPort ids;
    private final SubjectMappingExceptionRecord authorizationProbe;

    public SubjectRegistryService(
            SubjectRegistryRepository repository,
            IdentifierProtectionPort protection,
            SourceIdentifierPolicyPort sourcePolicy,
            SubjectRegistryIdempotencyPort idempotency,
            CompositeAuthorizationPort authorization,
            CompositeAuthorizationRecheckPort authorizationRecheck,
            SubjectRegistryTransactionPort transactions,
            SubjectRegistryAuditPort audit,
            SubjectRegistryOutboxPort outbox,
            AuditAvailabilityPort auditAvailability,
            TrustedTimeSource time,
            SubjectRegistryIdPort ids,
            SubjectMappingAuthorizationProbePort authorizationProbe) {
        this.repository = Objects.requireNonNull(repository);
        this.protection = Objects.requireNonNull(protection);
        this.sourcePolicy = Objects.requireNonNull(sourcePolicy);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.authorization = Objects.requireNonNull(authorization);
        this.authorizationRecheck = Objects.requireNonNull(authorizationRecheck);
        this.transactions = Objects.requireNonNull(transactions);
        this.audit = Objects.requireNonNull(audit);
        this.outbox = Objects.requireNonNull(outbox);
        this.auditAvailability = Objects.requireNonNull(auditAvailability);
        this.time = Objects.requireNonNull(time);
        this.ids = Objects.requireNonNull(ids);
        this.authorizationProbe = Objects.requireNonNull(
                Objects.requireNonNull(authorizationProbe).probe(), "authorization probe");
    }

    public IngestSubjectIdentifierResult ingest(IngestSubjectIdentifierCommand command) {
        Objects.requireNonNull(command);
        TrustedTime trusted = trustedTime();
        requireAuditHealthy(command.traceId(), trusted.instant());
        SourceIdentifierRule rule = sourcePolicy.requireApproved(
                command.sourceId(), command.sourceContractVersion(), command.identifierType());
        if (!rule.sourceId().equals(command.sourceId())
                || rule.identifierType() != command.identifierType()) {
            throw new SubjectRegistryApplicationException("SUBJECT_REGISTRY_POLICY_VERSION_UNKNOWN");
        }

        ProtectedIdentifierMaterial sourceMaterial;
        ProtectedIdentifierMaterial officialMaterial;
        try {
            String normalizedSource = IdentifierNormalizer.normalize(
                    command.sourceNativeIdentifier(), rule.normalizationProfile());
            String normalizedOfficial = IdentifierNormalizer.normalize(
                    command.authoritativeStudentNumber(),
                    NormalizationProfile.UPPERCASE_PRESERVE_LEADING_ZERO_V1);
            sourceMaterial = protection.protect(
                    normalizedSource,
                    new IdentifierProtectionContext(
                            command.sourceId(), command.identifierType().wireValue(),
                            "SOURCE_NATIVE_IDENTIFIER"));
            officialMaterial = protection.protect(
                    normalizedOfficial,
                    new IdentifierProtectionContext(
                            StudentRefIssuer.AUTHORITY_SOURCE_ID,
                            IdentifierType.STUDENT_NUMBER.wireValue(),
                            "STUDENT_OFFICIAL_REF"));
        } catch (IdentifierProtectionUnavailableException unavailable) {
            throw new SubjectRegistryApplicationException(
                    "SUBJECT_REGISTRY_PROTECTION_KEY_UNAVAILABLE");
        }

        IdentifierKey key = new IdentifierKey(
                command.sourceId(), command.identifierType(), sourceMaterial.token());
        List<StudentRef> candidates = List.copyOf(repository.findAuthorityCandidates(
                officialMaterial.token(), command.effectiveInterval().effectiveFrom()));
        boolean previouslyIssued = repository.identifierPreviouslyIssued(key);
        MappingExceptionCode exceptionCode = exceptionCode(rule, candidates, previouslyIssued);
        if (exceptionCode != null) {
            SubjectMappingException exception = SubjectMappingException.open(
                    ids.nextUuid(), key, exceptionCode, rule.sourceOwner(), trusted.instant());
            SubjectMappingExceptionRecord exceptionRecord =
                    new SubjectMappingExceptionRecord(exception, officialMaterial);
            executeIngest(
                    Optional.empty(), Optional.of(exceptionRecord), sourceMaterial,
                    command, trusted, exception.exceptionId(), 1, "isolated");
            return new IngestSubjectIdentifierResult(
                    exceptionCode == MappingExceptionCode.AMBIGUOUS
                            ? ResolutionOutcome.AMBIGUOUS : ResolutionOutcome.NO_MATCH,
                    Optional.empty(), Optional.of(exception.exceptionId()));
        }

        StudentRef studentRef = resolveOrIssue(rule, candidates, previouslyIssued);
        SubjectMapping mapping = SubjectMapping.active(
                ids.nextUuid(), ids.nextUuid(), key, studentRef,
                command.effectiveInterval(), command.sourceVersion());
        try {
            repository.timeline(key).append(mapping);
        } catch (SubjectRegistryException conflict) {
            throw mapDomain(conflict);
        }
        executeIngest(
                Optional.of(mapping), Optional.empty(), sourceMaterial,
                command, trusted, mapping.mappingId(), mapping.mappingVersion(), "mapped");
        return new IngestSubjectIdentifierResult(
                ResolutionOutcome.UNIQUE, Optional.of(studentRef), Optional.empty());
    }

    public List<SubjectMappingExceptionView> listExceptions(
            int offset, int limit, ActorContext actor, String traceId) {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new SubjectRegistryApplicationException("SUBJECT_REGISTRY_PAGE_INVALID");
        }
        List<SubjectMappingExceptionView> visible = new ArrayList<>(limit + 1);
        int rawOffset = 0;
        int authorizedOffset = 0;
        while (visible.size() < limit + 1) {
            List<SubjectMappingExceptionRecord> batch =
                    repository.listExceptions(rawOffset, MAX_SCAN_BATCH);
            if (batch.isEmpty()) break;
            for (SubjectMappingExceptionRecord record : batch) {
                AuthorizationResult result = authorize(
                        record, "data-quality.read", actor, traceId, false);
                if (result == null) continue;
                if (authorizedOffset++ < offset) continue;
                visible.add(project(record, result.clearOfficialRef()));
                if (visible.size() == limit + 1) break;
            }
            if (batch.size() < MAX_SCAN_BATCH) break;
            rawOffset = Math.addExact(rawOffset, batch.size());
        }
        return List.copyOf(visible);
    }

    public SubjectMappingExceptionView detailException(
            UUID exceptionId, ActorContext actor, String traceId) {
        AuthorizedRecord authorized = authorizedLoad(
                exceptionId, "data-quality.read", actor, traceId, false);
        return project(authorized.record(), authorized.authorization().clearOfficialRef());
    }

    public SubjectMappingExceptionView reconcileException(
            UUID exceptionId, ActorContext actor, String traceId) {
        AuthorizedRecord authorized = authorizedLoad(
                exceptionId, "data-quality.reconcile", actor, traceId, false);
        return project(authorized.record(), authorized.authorization().clearOfficialRef());
    }

    public RepairSubjectMappingResult repair(RepairSubjectMappingCommand command) {
        Objects.requireNonNull(command);
        AuthorizedRecord authorized = authorizedLoad(
                command.exceptionId(), "data-quality.repair", command.actor(),
                command.traceId(), true);
        TrustedTime trusted = trustedTime();
        requireAuditHealthy(command.traceId(), trusted.instant());
        RepairIdempotencyScope scope = new RepairIdempotencyScope(
                command.actor().tenantId(), command.actor().actorPseudonym(),
                "subject-mapping.repair", command.idempotencyKey());
        String requestDigest = requestDigest(command);
        RepairIdempotencyResult completed = idempotency.find(scope, trusted.instant()).orElse(null);
        if (completed != null) {
            if (!completed.requestDigest().equals(requestDigest)) {
                throw new SubjectRegistryApplicationException(
                        "SUBJECT_REGISTRY_IDEMPOTENCY_MISMATCH");
            }
            return completed.response();
        }

        try {
            return (RepairSubjectMappingResult) transactions.execute(() -> repairInTransaction(
                    command, authorized, trusted, scope, requestDigest));
        } catch (SubjectRegistryVersionConflictException conflict) {
            throw new SubjectRegistryApplicationException(
                    "SUBJECT_REGISTRY_VERSION_CONFLICT", conflict.currentVersion());
        } catch (SubjectRegistryException domain) {
            throw mapDomain(domain);
        }
    }

    private RepairSubjectMappingResult repairInTransaction(
            RepairSubjectMappingCommand command,
            AuthorizedRecord authorized,
            TrustedTime trusted,
            RepairIdempotencyScope scope,
            String requestDigest) {
        RepairIdempotencyClaim claim = idempotency.claim(
                scope, requestDigest, command.exceptionId(), trusted.instant());
        if (claim.status() == RepairIdempotencyClaim.Status.MISMATCH) {
            throw new SubjectRegistryApplicationException(
                    "SUBJECT_REGISTRY_IDEMPOTENCY_MISMATCH");
        }
        if (claim.status() == RepairIdempotencyClaim.Status.REPLAY) {
            return claim.result().orElseThrow().response();
        }

        SubjectMappingExceptionRecord current = repository.findException(command.exceptionId())
                .orElseThrow(() -> new SubjectRegistryApplicationException(FORBIDDEN));
        requireVersion(current.exception(), command.expectedVersion());
        recheck(authorized.authorization());

        SubjectMappingException reviewing = current.exception().status() == MappingExceptionStatus.OPEN
                ? current.exception().beginReview(trusted.instant())
                : current.exception();
        SubjectMappingException resolved = reviewing.resolve(
                MappingResolutionCode.MAPPING_CORRECTED, trusted.instant());
        UUID lineageId = ids.nextUuid();
        MappingCorrectionEvent correction = MappingCorrectionEvent.first(
                ids.nextUuid(), lineageId, command.correctionType(), command.subjectLink(),
                command.reason(), trusted.instant());
        LinkedHashSet<StudentRef> affected = new LinkedHashSet<>();
        affected.add(command.subjectLink().source());
        affected.addAll(command.subjectLink().targets());
        MappingRecomputeRequestIntent recompute = new MappingRecomputeRequestIntent(
                ids.nextUuid(), lineageId, List.copyOf(affected), command.sourceWatermark(),
                trusted.instant(), command.traceId());
        SubjectMappingExceptionRecord updated = new SubjectMappingExceptionRecord(
                resolved, current.officialIdentifier());
        SubjectRegistryAuditEvent auditEvent = new SubjectRegistryAuditEvent(
                "subject-mapping.repair", "resolved", command.exceptionId(),
                resolved.aggregateVersion(), command.actor().auditActorRef(),
                command.actor().sourceIp(), command.traceId(), trusted.instant(),
                trusted.profile(), digest(command.idempotencyKey(), true));
        RepairSubjectMappingResult persisted = repository.saveRepair(
                new RepairCommit(
                        updated, correction, recompute, scope, requestDigest, auditEvent),
                command.expectedVersion());
        outbox.appendCorrection(correction);
        outbox.appendRecomputeRequest(recompute);
        audit.append(auditEvent);
        RepairSubjectMappingResult response = persisted;
        idempotency.complete(new RepairIdempotencyResult(
                scope, requestDigest, response, trusted.instant(),
                trusted.instant().plus(IDEMPOTENCY_RETENTION)));
        return response;
    }

    private void executeIngest(
            Optional<SubjectMapping> mapping,
            Optional<SubjectMappingExceptionRecord> exceptionRecord,
            ProtectedIdentifierMaterial sourceIdentifier,
            IngestSubjectIdentifierCommand command,
            TrustedTime trusted,
            UUID objectId,
            long version,
            String result) {
        SubjectRegistryAuditEvent auditEvent = new SubjectRegistryAuditEvent(
                "subject-mapping.ingest", result, objectId, version,
                command.actor().auditActorRef(), command.actor().sourceIp(),
                command.traceId(), trusted.instant(), trusted.profile(), null);
        IngestCommit commit = new IngestCommit(
                mapping, exceptionRecord, sourceIdentifier,
                command.sourceWatermark(), auditEvent);
        transactions.execute(() -> {
            repository.saveIngest(commit);
            audit.append(auditEvent);
            return null;
        });
    }

    private MappingExceptionCode exceptionCode(
            SourceIdentifierRule rule, List<StudentRef> candidates, boolean previouslyIssued) {
        if (candidates.size() > 1) return MappingExceptionCode.AMBIGUOUS;
        if (previouslyIssued) return MappingExceptionCode.REISSUE_UNPROVEN;
        if (candidates.isEmpty() && !rule.mayIssueStudentRef()) return MappingExceptionCode.NO_MATCH;
        return null;
    }

    private StudentRef resolveOrIssue(
            SourceIdentifierRule rule, List<StudentRef> candidates, boolean previouslyIssued) {
        if (!candidates.isEmpty()) return candidates.getFirst();
        StudentRef candidate = ids.nextStudentRef();
        AuthorityEvidence evidence = new AuthorityEvidence(
                rule.sourceId(), rule.identifierType(), 1,
                rule.mayIssueStudentRef(), previouslyIssued, !previouslyIssued);
        Set<StudentRef> reservations = repository.isStudentRefReserved(candidate)
                ? Set.of(candidate) : Set.of();
        try {
            return StudentRefIssuer.issue(evidence, candidate, reservations);
        } catch (SubjectRegistryException invalid) {
            throw mapDomain(invalid);
        }
    }

    private AuthorizedRecord authorizedLoad(
            UUID exceptionId, String action, ActorContext actor,
            String traceId, boolean requireRepairProjection) {
        SubjectMappingExceptionRecord existing = repository.findException(exceptionId).orElse(null);
        SubjectMappingExceptionRecord authorizationObject = existing == null
                ? authorizationProbe : existing;
        AuthorizationResult result = authorize(
                authorizationObject, action, actor, traceId, true, exceptionId);
        if (result == null || (requireRepairProjection && !result.clearOfficialRef())) {
            throw new SubjectRegistryApplicationException(FORBIDDEN);
        }
        if (existing == null) throw new SubjectRegistryApplicationException(FORBIDDEN);
        return new AuthorizedRecord(existing, result);
    }

    private AuthorizationResult authorize(
            SubjectMappingExceptionRecord record, String action, ActorContext actor,
            String traceId, boolean throwOnDeny) {
        return authorize(
                record, action, actor, traceId, throwOnDeny,
                record.exception().exceptionId());
    }

    private AuthorizationResult authorize(
            SubjectMappingExceptionRecord record, String action, ActorContext actor,
            String traceId, boolean throwOnDeny, UUID requestedObjectId) {
        CompositeAuthorizationRequest request = new CompositeAuthorizationRequest(
                actor.actorPseudonym(), "SUBJECT_MAPPING_EXCEPTION", action,
                digest(record.exception().identifierKey().sourceId(), false),
                record.exception().aggregateVersion(),
                Optional.of(digest(record.exception().identifierKey().sourceId(), false)),
                Optional.empty(), traceId);
        CompositeAuthorizationDecision decision;
        try {
            decision = Objects.requireNonNull(authorization.authorize(request));
        } catch (RuntimeException unavailable) {
            throw new SubjectRegistryApplicationException(DEPENDENCY_UNAVAILABLE);
        }
        if (decision.outcome() == CompositeAuthorizationOutcome.DEPENDENCY_UNAVAILABLE) {
            throw new SubjectRegistryApplicationException(DEPENDENCY_UNAVAILABLE);
        }
        if (decision.outcome() != CompositeAuthorizationOutcome.ALLOW
                || decision.objectVersion() != record.exception().aggregateVersion()) {
            if (throwOnDeny) throw new SubjectRegistryApplicationException(FORBIDDEN);
            return null;
        }
        return new AuthorizationResult(
                request, decision,
                decision.clearConditionalFields().contains("subjectOfficialRef"));
    }

    private void recheck(AuthorizationResult authorized) {
        try {
            var result = Objects.requireNonNull(authorizationRecheck.recheck(
                    new CompositeAuthorizationRecheckRequest(
                            authorized.request(), authorized.decision().decisionToken())));
            if (result.outcome() == CompositeAuthorizationRecheckOutcome.DEPENDENCY_UNAVAILABLE) {
                throw new SubjectRegistryApplicationException(DEPENDENCY_UNAVAILABLE);
            }
            if (result.outcome() != CompositeAuthorizationRecheckOutcome.CURRENT) {
                throw new SubjectRegistryApplicationException(FORBIDDEN);
            }
        } catch (SubjectRegistryApplicationException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new SubjectRegistryApplicationException(DEPENDENCY_UNAVAILABLE);
        }
    }

    private SubjectMappingExceptionView project(
            SubjectMappingExceptionRecord record, boolean clearOfficialRef) {
        Optional<String> officialRef = Optional.empty();
        if (clearOfficialRef) {
            try {
                officialRef = Optional.of(protection.reveal(record.officialIdentifier()));
            } catch (IdentifierProtectionUnavailableException unavailable) {
                throw new SubjectRegistryApplicationException(
                        "SUBJECT_REGISTRY_PROTECTION_KEY_UNAVAILABLE");
            }
        }
        SubjectMappingException exception = record.exception();
        return new SubjectMappingExceptionView(
                exception.exceptionId(), exception.status(), officialRef,
                exception.exceptionCode(), exception.identifierKey().sourceId(),
                exception.sourceOwner(), exception.detectedAt(), exception.aggregateVersion());
    }

    private TrustedTime trustedTime() {
        try {
            return Objects.requireNonNull(time.now());
        } catch (RuntimeException unavailable) {
            throw new SubjectRegistryApplicationException(
                    "SUBJECT_REGISTRY_TIME_SOURCE_UNAVAILABLE");
        }
    }

    private void requireAuditHealthy(String traceId, Instant now) {
        try {
            if (!Objects.requireNonNull(auditAvailability.current(traceId)).allowsHighRiskAt(now)) {
                throw new SubjectRegistryApplicationException(
                        "SUBJECT_REGISTRY_AUDIT_UNAVAILABLE");
            }
        } catch (SubjectRegistryApplicationException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw new SubjectRegistryApplicationException(
                    "SUBJECT_REGISTRY_AUDIT_UNAVAILABLE");
        }
    }

    private static void requireVersion(SubjectMappingException exception, long expectedVersion) {
        if (exception.aggregateVersion() != expectedVersion) {
            throw new SubjectRegistryApplicationException(
                    "SUBJECT_REGISTRY_VERSION_CONFLICT", exception.aggregateVersion());
        }
    }

    private static String requestDigest(RepairSubjectMappingCommand command) {
        List<String> targets = command.subjectLink().targets().stream()
                .map(Object::toString).sorted().toList();
        String canonical = String.join("\n",
                command.exceptionId().toString(),
                Long.toString(command.expectedVersion()),
                command.reason().name(),
                command.sourceWatermark(),
                command.correctionType().name(),
                command.subjectLink().type().name(),
                command.subjectLink().source().toString(),
                String.join(",", targets));
        return digest(canonical, true);
    }

    private static String digest(String value, boolean prefix) {
        try {
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
            return prefix ? "sha256:" + hex : hex;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static SubjectRegistryApplicationException mapDomain(SubjectRegistryException error) {
        return new SubjectRegistryApplicationException(error.code().name());
    }

    private record AuthorizationResult(
            CompositeAuthorizationRequest request,
            CompositeAuthorizationDecision decision,
            boolean clearOfficialRef) {}

    private record AuthorizedRecord(
            SubjectMappingExceptionRecord record,
            AuthorizationResult authorization) {}
}
