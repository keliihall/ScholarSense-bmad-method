package cn.edu.suda.scholarsense.subjectregistry.adapters.outbound;

import cn.edu.suda.scholarsense.subjectregistry.application.IngestCommit;
import cn.edu.suda.scholarsense.subjectregistry.application.ProtectedIdentifierMaterial;
import cn.edu.suda.scholarsense.subjectregistry.application.RepairCommit;
import cn.edu.suda.scholarsense.subjectregistry.application.RepairIdempotencyClaim;
import cn.edu.suda.scholarsense.subjectregistry.application.RepairIdempotencyResult;
import cn.edu.suda.scholarsense.subjectregistry.application.RepairIdempotencyScope;
import cn.edu.suda.scholarsense.subjectregistry.application.RepairSubjectMappingResult;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectMappingExceptionRecord;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryApplicationException;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryAuditEvent;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryAuditPort;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryIdPort;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryIdempotencyPort;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryOutboxPort;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryRepository;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectRegistryVersionConflictException;
import cn.edu.suda.scholarsense.subjectregistry.domain.CorrectionType;
import cn.edu.suda.scholarsense.subjectregistry.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierKey;
import cn.edu.suda.scholarsense.subjectregistry.domain.IdentifierType;
import cn.edu.suda.scholarsense.subjectregistry.domain.LinkType;
import cn.edu.suda.scholarsense.subjectregistry.domain.MappingCorrectionEvent;
import cn.edu.suda.scholarsense.subjectregistry.domain.MappingExceptionCode;
import cn.edu.suda.scholarsense.subjectregistry.domain.MappingExceptionStatus;
import cn.edu.suda.scholarsense.subjectregistry.domain.MappingResolutionCode;
import cn.edu.suda.scholarsense.subjectregistry.domain.ProtectedIdentifierToken;
import cn.edu.suda.scholarsense.subjectregistry.domain.StudentRef;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMapping;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMappingException;
import cn.edu.suda.scholarsense.subjectregistry.domain.SubjectMappingTimeline;
import cn.edu.suda.scholarsense.subjectregistry.api.PendingSubjectRecomputeRequest;
import cn.edu.suda.scholarsense.subjectregistry.api.PendingSubjectRecomputeRequestPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL adapter for the subject-registry owner schema.
 * All writes go through security-definer functions which also persist audit/outbox facts.
 */
public final class JdbcSubjectRegistryStore implements
        SubjectRegistryRepository,
        SubjectRegistryIdempotencyPort,
        SubjectRegistryAuditPort,
        SubjectRegistryOutboxPort,
        PendingSubjectRecomputeRequestPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SubjectRegistryIdPort ids;

    public JdbcSubjectRegistryStore(
            JdbcTemplate jdbc, ObjectMapper json, SubjectRegistryIdPort ids) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.json = java.util.Objects.requireNonNull(json);
        this.ids = java.util.Objects.requireNonNull(ids);
    }

    @Override
    public List<StudentRef> findAuthorityCandidates(
            ProtectedIdentifierToken authorityToken, Instant at) {
        return jdbc.query("""
                select distinct mapping.student_ref
                  from subject_registry.sr_identifier_secret secret
                  join subject_registry.sr_subject_mapping mapping
                    on mapping.identifier_id=secret.identifier_id
                 where secret.source_id='SRC-P0-STUDENT-001'
                   and secret.identifier_type='student-number'
                   and secret.environment=? and secret.key_ref=? and secret.key_version=?
                   and secret.protected_identifier_token=?
                   and mapping.effective_period @> ?::timestamptz
                 order by mapping.student_ref
                """, (row, ignored) -> StudentRef.of(row.getObject(1, UUID.class)),
                authorityToken.environment(), authorityToken.keyRef(), authorityToken.keyVersion(),
                authorityToken.value(), java.sql.Timestamp.from(at));
    }

    @Override
    public boolean identifierPreviouslyIssued(IdentifierKey key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(
                  select 1 from subject_registry.sr_identifier_secret
                   where source_id=? and identifier_type=? and environment=?
                     and key_ref=? and key_version=? and protected_identifier_token=?)
                """, Boolean.class, key.sourceId(), key.identifierType().wireValue(),
                key.token().environment(), key.token().keyRef(), key.token().keyVersion(),
                key.token().value()));
    }

    @Override
    public boolean isStudentRefReserved(StudentRef studentRef) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from subject_registry.sr_student_ref_reservation
                               where student_ref=?)
                """, Boolean.class, studentRef.value()));
    }

    @Override
    public SubjectMappingTimeline timeline(IdentifierKey key) {
        List<SubjectMapping> mappings = jdbc.query("""
                select mapping.mapping_id, mapping.mapping_aggregate_id, mapping.student_ref,
                       lower(mapping.effective_period), upper(mapping.effective_period),
                       mapping.mapping_version
                  from subject_registry.sr_identifier_secret secret
                  join subject_registry.sr_subject_mapping mapping
                    on mapping.identifier_id=secret.identifier_id
                 where secret.source_id=? and secret.identifier_type=? and secret.environment=?
                   and secret.key_ref=? and secret.key_version=?
                   and secret.protected_identifier_token=?
                 order by lower(mapping.effective_period), mapping.mapping_version
                """, (row, ignored) -> SubjectMapping.active(
                        row.getObject(1, UUID.class), row.getObject(2, UUID.class), key,
                        StudentRef.of(row.getObject(3, UUID.class)),
                        EffectiveInterval.of(
                                row.getTimestamp(4).toInstant(),
                                row.getTimestamp(5) == null ? null : row.getTimestamp(5).toInstant()),
                        row.getLong(6)),
                key.sourceId(), key.identifierType().wireValue(), key.token().environment(),
                key.token().keyRef(), key.token().keyVersion(), key.token().value());
        return SubjectMappingTimeline.restoreForIsolation(mappings);
    }

    @Override
    public Optional<SubjectMappingExceptionRecord> findException(UUID exceptionId) {
        List<SubjectMappingExceptionRecord> rows = queryExceptions(
                "where exception.exception_id=?", exceptionId);
        return rows.stream().findFirst();
    }

    @Override
    public List<SubjectMappingExceptionRecord> listExceptions(int offset, int limit) {
        return queryExceptions(
                "order by exception.detected_at desc, exception.exception_id offset ? limit ?",
                offset, limit);
    }

    private List<SubjectMappingExceptionRecord> queryExceptions(String suffix, Object... arguments) {
        return jdbc.query("""
                select exception.exception_id, exception.exception_code, exception.source_owner,
                       exception.status, exception.resolution_code, exception.aggregate_version,
                       exception.detected_at, exception.updated_at,
                       source.source_id, source.identifier_type, source.environment,
                       source.key_ref, source.key_version, source.protected_identifier_token,
                       official.environment, official.key_ref, official.key_version,
                       official.protected_identifier_token, official.ciphertext, official.purpose
                  from subject_registry.sr_mapping_exception exception
                  join subject_registry.sr_identifier_secret source
                    on source.identifier_id=exception.identifier_id
                  join subject_registry.sr_identifier_secret official
                    on official.identifier_id=exception.official_identifier_id
                """ + suffix, (row, ignored) -> {
                    ProtectedIdentifierToken sourceToken = ProtectedIdentifierToken.of(
                            row.getString(11), row.getString(12), row.getString(13), row.getString(14));
                    IdentifierKey key = new IdentifierKey(
                            row.getString(9), identifierType(row.getString(10)), sourceToken);
                    MappingExceptionStatus status = status(row.getString(4));
                    MappingResolutionCode resolution = resolution(row.getString(5));
                    SubjectMappingException exception = new SubjectMappingException(
                            row.getObject(1, UUID.class), key, exceptionCode(row.getString(2)),
                            row.getString(3), status, resolution, row.getLong(6),
                            row.getTimestamp(7).toInstant(), row.getTimestamp(8).toInstant());
                    ProtectedIdentifierMaterial official = new ProtectedIdentifierMaterial(
                            ProtectedIdentifierToken.of(
                                    row.getString(15), row.getString(16), row.getString(17),
                                    row.getString(18)),
                            row.getString(19), row.getString(20));
                    return new SubjectMappingExceptionRecord(exception, official);
                }, arguments);
    }

    @Override
    public void saveIngest(IngestCommit commit) {
        try {
            if (commit.mapping().isPresent()) {
                saveMapping(commit, commit.mapping().orElseThrow());
            } else {
                saveException(commit, commit.exceptionRecord().orElseThrow());
            }
        } catch (DataAccessException failure) {
            throw translate(failure, null);
        }
    }

    private void saveMapping(IngestCommit commit, SubjectMapping mapping) {
        ProtectedIdentifierMaterial material = commit.sourceIdentifier();
        SubjectRegistryAuditEvent audit = commit.auditEvent();
        jdbc.queryForObject("""
                select subject_registry.sr_record_subject_mapping(
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Boolean.class,
                ids.nextUuid(), mapping.mappingId(), mapping.mappingAggregateId(),
                mapping.studentRef().value(), mapping.identifierKey().sourceId(),
                mapping.identifierKey().identifierType().wireValue(),
                material.token().environment(), material.token().keyRef(),
                material.token().keyVersion(), material.token().value(), material.ciphertext(),
                material.purpose(), java.sql.Timestamp.from(mapping.effectiveInterval().effectiveFrom()),
                mapping.effectiveInterval().effectiveTo() == null ? null
                        : java.sql.Timestamp.from(mapping.effectiveInterval().effectiveTo()),
                mapping.mappingVersion(), java.sql.Timestamp.from(audit.occurredAt()),
                ids.nextUuid(), ids.nextUuid(), audit.auditActorRef(), audit.traceId());
    }

    private void saveException(IngestCommit commit, SubjectMappingExceptionRecord record) {
        ProtectedIdentifierMaterial source = commit.sourceIdentifier();
        ProtectedIdentifierMaterial official = record.officialIdentifier();
        SubjectMappingException exception = record.exception();
        SubjectRegistryAuditEvent audit = commit.auditEvent();
        jdbc.queryForObject("""
                select subject_registry.sr_record_mapping_exception(
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Boolean.class,
                ids.nextUuid(), exception.exceptionId(), exception.identifierKey().sourceId(),
                exception.identifierKey().identifierType().wireValue(), source.token().environment(),
                source.token().keyRef(), source.token().keyVersion(), source.token().value(),
                source.ciphertext(), source.purpose(), ids.nextUuid(),
                official.token().environment(), official.token().keyRef(),
                official.token().keyVersion(), official.token().value(), official.ciphertext(),
                exceptionCode(exception.exceptionCode()), exception.sourceOwner(),
                java.sql.Timestamp.from(exception.detectedAt()), ids.nextUuid(), ids.nextUuid(),
                audit.auditActorRef(), audit.traceId());
    }

    @Override
    public RepairSubjectMappingResult saveRepair(RepairCommit commit, long expectedVersion) {
        MappingCorrectionEvent event = commit.correctionEvent();
        SubjectRegistryAuditEvent audit = commit.auditEvent();
        try {
            String response = jdbc.queryForObject("""
                    select subject_registry.sr_repair_mapping_exception(
                      ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)::text
                    """, String.class,
                    commit.exceptionRecord().exception().exceptionId(), expectedVersion,
                    scopeDigest(commit.idempotencyScope()), commit.requestDigest(), event.eventId(),
                    event.lineageId(), event.correctionType().name().toLowerCase(java.util.Locale.ROOT),
                    linkType(event.subjectLink().type()), event.subjectLink().source().value(),
                    event.subjectLink().targets().stream().map(StudentRef::value).toArray(UUID[]::new),
                    event.reason().name(), java.sql.Timestamp.from(event.effectiveAt()),
                    commit.recomputeRequest().requestId(), commit.recomputeRequest().sourceWatermark(),
                    ids.nextUuid(), ids.nextUuid(), audit.auditActorRef(), audit.traceId());
            JsonNode node = json.readTree(response);
            return new RepairSubjectMappingResult(
                    UUID.fromString(node.get("exceptionId").asText()),
                    MappingExceptionStatus.RESOLVED, node.get("aggregateVersion").asLong(),
                    UUID.fromString(node.get("correctionEventId").asText()),
                    UUID.fromString(node.get("recomputeRequestId").asText()));
        } catch (DataAccessException failure) {
            throw translate(failure, commit.exceptionRecord().exception().exceptionId());
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new SubjectRegistryApplicationException("SUBJECT_REGISTRY_DEPENDENCY_UNAVAILABLE");
        }
    }

    @Override
    public Optional<RepairIdempotencyResult> find(RepairIdempotencyScope scope, Instant at) {
        List<RepairIdempotencyResult> rows = jdbc.query("""
                select request_digest, response::text, created_at, expires_at
                  from subject_registry.sr_repair_idempotency
                 where idempotency_scope_digest=? and expires_at>?
                   and response is not null
                """, (row, ignored) -> {
                    try {
                        JsonNode node = json.readTree(row.getString(2));
                        RepairSubjectMappingResult response = new RepairSubjectMappingResult(
                                UUID.fromString(node.get("exceptionId").asText()),
                                MappingExceptionStatus.RESOLVED,
                                node.get("aggregateVersion").asLong(),
                                UUID.fromString(node.get("correctionEventId").asText()),
                                UUID.fromString(node.get("recomputeRequestId").asText()));
                        return new RepairIdempotencyResult(
                                scope, row.getString(1).trim(), response,
                                row.getTimestamp(3).toInstant(), row.getTimestamp(4).toInstant());
                    } catch (tools.jackson.core.JacksonException invalid) {
                        throw new SubjectRegistryApplicationException(
                                "SUBJECT_REGISTRY_DEPENDENCY_UNAVAILABLE");
                    }
                }, scopeDigest(scope), java.sql.Timestamp.from(at));
        return rows.stream().findFirst();
    }

    @Override
    public RepairIdempotencyClaim claim(
            RepairIdempotencyScope scope, String requestDigest, UUID exceptionId, Instant at) {
        RepairIdempotencyResult current = find(scope, at).orElse(null);
        if (current == null) return RepairIdempotencyClaim.fresh();
        return current.requestDigest().equals(requestDigest)
                ? RepairIdempotencyClaim.replay(current) : RepairIdempotencyClaim.mismatch();
    }

    @Override
    public void complete(RepairIdempotencyResult ignored) {
        // sr_repair_mapping_exception stores the completed response in the same transaction.
    }

    @Override
    public void append(SubjectRegistryAuditEvent ignored) {
        // Subject-registry security-definer write functions persist local audit + outbox atomically.
    }

    @Override
    public void appendCorrection(MappingCorrectionEvent ignored) {
        // sr_repair_mapping_exception persists the correction event atomically.
    }

    @Override
    public void appendRecomputeRequest(
            cn.edu.suda.scholarsense.subjectregistry.application.MappingRecomputeRequestIntent ignored) {
        // sr_repair_mapping_exception persists the recompute request atomically.
    }

    @Override
    public Optional<PendingSubjectRecomputeRequest> findPendingRequest(UUID requestId) {
        List<PendingSubjectRecomputeRequest> rows = jdbc.query("""
                select request_id, owner_source_id, queued_at, trace_id
                  from subject_registry.sr_find_pending_recompute_request(?)
                """, (row, ignored) -> new PendingSubjectRecomputeRequest(
                        row.getObject(1, UUID.class), row.getString(2),
                        row.getTimestamp(3).toInstant(), row.getString(4)), requestId);
        return rows.stream().findFirst();
    }

    private RuntimeException translate(DataAccessException failure, UUID exceptionId) {
        String message = failure.getMostSpecificCause().getMessage();
        if (message != null && message.contains("SUBJECT_REGISTRY_VERSION_CONFLICT")) {
            Long version = exceptionId == null ? null : jdbc.queryForObject("""
                    select aggregate_version from subject_registry.sr_mapping_exception
                     where exception_id=?
                    """, Long.class, exceptionId);
            return new SubjectRegistryVersionConflictException(version == null ? 1 : version);
        }
        if (message != null && message.contains("SUBJECT_REGISTRY_IDEMPOTENCY_MISMATCH")) {
            return new SubjectRegistryApplicationException("SUBJECT_REGISTRY_IDEMPOTENCY_MISMATCH");
        }
        if (message != null && (message.contains("iq_historical_window_no_overlap")
                || message.contains("sr_subject_mapping_identifier_id_effective_period_excl"))) {
            return new SubjectRegistryApplicationException("SUBJECT_REGISTRY_MAPPING_OVERLAP");
        }
        if (message != null && message.contains("SUBJECT_REGISTRY_FORBIDDEN")) {
            return new SubjectRegistryApplicationException("SUBJECT_REGISTRY_FORBIDDEN");
        }
        return new SubjectRegistryApplicationException("SUBJECT_REGISTRY_DEPENDENCY_UNAVAILABLE");
    }

    private static String scopeDigest(RepairIdempotencyScope scope) {
        return digest(scope.tenantId() + "\0" + scope.actorPseudonym() + "\0"
                + scope.commandType() + "\0" + scope.idempotencyKey());
    }

    private static String digest(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IdentifierType identifierType(String value) {
        for (IdentifierType type : IdentifierType.values()) {
            if (type.wireValue().equals(value)) return type;
        }
        throw new SubjectRegistryApplicationException("SUBJECT_REGISTRY_DEPENDENCY_UNAVAILABLE");
    }

    private static MappingExceptionStatus status(String value) {
        return switch (value) {
            case "open" -> MappingExceptionStatus.OPEN;
            case "in-review" -> MappingExceptionStatus.IN_REVIEW;
            case "resolved" -> MappingExceptionStatus.RESOLVED;
            case "dismissed" -> MappingExceptionStatus.DISMISSED;
            default -> throw new SubjectRegistryApplicationException(
                    "SUBJECT_REGISTRY_DEPENDENCY_UNAVAILABLE");
        };
    }

    private static MappingResolutionCode resolution(String value) {
        if (value == null) return null;
        return switch (value) {
            case "linked-existing" -> MappingResolutionCode.LINKED_TO_EXISTING_STUDENT;
            case "issued-new" -> MappingResolutionCode.ISSUED_NEW_STUDENT_REF;
            case "mapping-corrected" -> MappingResolutionCode.MAPPING_CORRECTED;
            case "false-positive-dismissed" -> MappingResolutionCode.FALSE_POSITIVE_DISMISSED;
            default -> throw new SubjectRegistryApplicationException(
                    "SUBJECT_REGISTRY_DEPENDENCY_UNAVAILABLE");
        };
    }

    private static MappingExceptionCode exceptionCode(String value) {
        return switch (value) {
            case "no-match" -> MappingExceptionCode.NO_MATCH;
            case "ambiguous" -> MappingExceptionCode.AMBIGUOUS;
            case "interval-overlap" -> MappingExceptionCode.INTERVAL_OVERLAP;
            case "version-regression" -> MappingExceptionCode.VERSION_REGRESSION;
            case "reissue-unproven" -> MappingExceptionCode.REISSUE_UNPROVEN;
            case "revocation-chain-incomplete" -> MappingExceptionCode.REVOCATION_CHAIN_INCOMPLETE;
            default -> throw new SubjectRegistryApplicationException(
                    "SUBJECT_REGISTRY_DEPENDENCY_UNAVAILABLE");
        };
    }

    private static String exceptionCode(MappingExceptionCode value) {
        return switch (value) {
            case NO_MATCH -> "no-match";
            case AMBIGUOUS -> "ambiguous";
            case INTERVAL_OVERLAP -> "interval-overlap";
            case VERSION_REGRESSION -> "version-regression";
            case REISSUE_UNPROVEN -> "reissue-unproven";
            case REVOCATION_CHAIN_INCOMPLETE -> "revocation-chain-incomplete";
        };
    }

    private static String linkType(LinkType value) {
        return switch (value) {
            case ALIAS -> "alias";
            case MERGED_INTO -> "merged-into";
            case SPLIT_INTO -> "split-into";
        };
    }
}
