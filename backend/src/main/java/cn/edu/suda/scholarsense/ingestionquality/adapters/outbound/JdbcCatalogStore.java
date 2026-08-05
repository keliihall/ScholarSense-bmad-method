package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationDomain;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizationPort;
import cn.edu.suda.scholarsense.identityaccess.api.AuditTokenizedValue;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditEvent;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditPort;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogCurrentPointer;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogEvidenceSet;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogIdempotencyClaim;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogIdempotencyPort;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogIdempotencyResult;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogRepository;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogVersionConflictException;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogView;
import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogValidationFailure;
import cn.edu.suda.scholarsense.ingestionquality.domain.DataSourceCatalog;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyBinding;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyOperator;
import cn.edu.suda.scholarsense.ingestionquality.domain.DependencyRequirement;
import cn.edu.suda.scholarsense.ingestionquality.domain.RuntimeEvidenceClaim;
import cn.edu.suda.scholarsense.ingestionquality.domain.SourceContract;
import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditFact;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import cn.edu.suda.scholarsense.shared.time.TrustedTime;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeException;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** JDBC owner adapter. Callers wrap every command in JdbcCatalogTransactionAdapter. */
public final class JdbcCatalogStore
        implements CatalogRepository, CatalogIdempotencyPort, CatalogAuditPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditTokenizationPort tokenization;
    private final TrustedTimeSource time;

    public JdbcCatalogStore(
            JdbcTemplate jdbc,
            ObjectMapper json,
            AuditTokenizationPort tokenization,
            TrustedTimeSource time) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
        this.tokenization = Objects.requireNonNull(tokenization);
        this.time = Objects.requireNonNull(time);
    }

    @Override
    public Optional<DataSourceCatalog> find(UUID catalogId) {
        return jdbc(() -> {
        List<DataSourceCatalog> values = jdbc.query("""
                select catalog_id,catalog_release_id,contract_version,status,aggregate_version,
                       content_digest,evidence_set_digest,validation_errors,created_at,updated_at,published_at
                  from ingestion_quality.iq_data_source_catalog where catalog_id=?
                """, (row, ignored) -> DataSourceCatalog.restore(
                    row.getObject("catalog_id", UUID.class),
                    row.getObject("catalog_release_id", UUID.class),
                    row.getString("contract_version"),
                    sources(catalogId), dependencies(catalogId),
                    row.getString("content_digest"), row.getString("evidence_set_digest"),
                    CatalogStatus.valueOf(row.getString("status").toUpperCase(java.util.Locale.ROOT)),
                    row.getLong("aggregate_version"),
                    failures(row.getString("validation_errors")),
                    row.getTimestamp("created_at").toInstant(),
                    row.getTimestamp("updated_at").toInstant(),
                    instant(row.getTimestamp("published_at"))), catalogId);
        return values.stream().findFirst();
        });
    }

    @Override
    public List<DataSourceCatalog> list(int offset, int limit) {
        return jdbc(() -> jdbc.query("""
                select catalog_id from ingestion_quality.iq_data_source_catalog
                 order by updated_at desc,catalog_id limit ? offset ?
                """, (row, ignored) -> row.getObject(1, UUID.class), limit, offset).stream()
                .map(this::find).flatMap(Optional::stream).toList());
    }

    @Override
    public Optional<CatalogCurrentPointer> currentPointer() {
        return jdbc(() -> jdbc.query("""
                select catalog_id,aggregate_version,pointer_version
                  from ingestion_quality.iq_catalog_current where singleton=true
                """, (row, ignored) -> new CatalogCurrentPointer(
                    row.getObject("catalog_id", UUID.class),
                    row.getLong("aggregate_version"), row.getLong("pointer_version")))
                .stream().findFirst());
    }

    @Override
    public void save(DataSourceCatalog catalog, long expectedVersion) {
        jdbcRun(() -> {
        requireVersion(expectedVersion, true);
        if (catalog.status() == CatalogStatus.PUBLISHED) {
            throw new IllegalArgumentException("INGESTION_QUALITY_PUBLISH_REPOSITORY_REQUIRED");
        }
        if (expectedVersion == 0) {
            insert(catalog);
            return;
        }
        int updated = jdbc.update("""
                update ingestion_quality.iq_data_source_catalog
                   set catalog_release_id=?,status=?,aggregate_version=?,evidence_set_digest=?,
                       validation_errors=?::jsonb,updated_at=?,published_at=?
                 where catalog_id=? and aggregate_version=? and status<>'published'
                """, catalog.catalogReleaseId(), db(catalog.status()), catalog.aggregateVersion(),
                catalog.evidenceSetDigest(), write(catalog.validationFailures()),
                Timestamp.from(catalog.updatedAt()), timestamp(catalog.publishedAt()),
                catalog.catalogId(), expectedVersion);
        if (updated != 1) {
            long current = find(catalog.catalogId()).map(DataSourceCatalog::aggregateVersion).orElse(0L);
            throw new CatalogVersionConflictException(current);
        }
        });
    }

    @Override
    public void saveValidation(
            DataSourceCatalog catalog, long expectedVersion, String traceId) {
        jdbcRun(() -> {
        requireVersion(expectedVersion, false);
        if (catalog.status() != CatalogStatus.INVALID
                && catalog.status() != CatalogStatus.PUBLISHABLE) {
            throw new IllegalArgumentException("INGESTION_QUALITY_VALIDATION_RESULT_REQUIRED");
        }
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("INGESTION_QUALITY_TRACE_ID_INVALID");
        }
        Boolean updated = jdbc.queryForObject("""
                select ingestion_quality.iq_record_catalog_validation(
                  ?,?,?,?,?::jsonb,?,?,?)
                """, Boolean.class, catalog.catalogId(), expectedVersion,
                db(catalog.status()), catalog.aggregateVersion(),
                write(catalog.validationFailures()), Timestamp.from(catalog.updatedAt()),
                CatalogUuidV7.generate(catalog.updatedAt()), traceId);
        if (!Boolean.TRUE.equals(updated)) {
            long current = find(catalog.catalogId())
                    .map(DataSourceCatalog::aggregateVersion).orElse(0L);
            throw new CatalogVersionConflictException(current);
        }
        });
    }

    @Override
    public void publish(
            DataSourceCatalog catalog, long expectedVersion, long expectedCurrentVersion,
            CatalogEvidenceSet evidence) {
        jdbcRun(() -> {
        requireVersion(expectedVersion, false);
        requireVersion(expectedCurrentVersion, true);
        if (catalog.status() != CatalogStatus.PUBLISHED) {
            throw new IllegalArgumentException("INGESTION_QUALITY_PUBLISHED_CATALOG_REQUIRED");
        }
        Boolean published = jdbc.queryForObject("""
                select ingestion_quality.iq_publish_catalog(
                  ?,?,?,?,?,?,?,?::jsonb)
                """, Boolean.class, catalog.catalogId(), expectedVersion,
                catalog.aggregateVersion(), catalog.catalogReleaseId(),
                catalog.evidenceSetDigest(), Timestamp.from(catalog.publishedAt()),
                expectedCurrentVersion, writeEvidence(evidence));
        if (!Boolean.TRUE.equals(published)) {
            Optional<DataSourceCatalog> currentCatalog = find(catalog.catalogId());
            if (currentCatalog.isEmpty()
                    || currentCatalog.get().aggregateVersion() != expectedVersion
                    || currentCatalog.get().status() != CatalogStatus.PUBLISHABLE) {
                throw new CatalogVersionConflictException(
                        currentCatalog.map(DataSourceCatalog::aggregateVersion).orElse(0L));
            }
            throw new CatalogVersionConflictException(currentPointer()
                    .map(CatalogCurrentPointer::pointerVersion).orElse(0L));
        }
        });
    }

    private String writeEvidence(CatalogEvidenceSet evidence) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CatalogEvidence item : evidence.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("evidence_id", CatalogUuidV7.generate(item.occurredAt()).toString());
            row.put("source_id", item.sourceId());
            row.put("evidence_uri", item.evidenceUri());
            row.put("evidence_digest", item.evidenceDigest());
            row.put("environment", item.environment());
            row.put("authority", item.authority());
            row.put("candidate_commit", item.candidateCommit());
            row.put("candidate_tree", item.candidateTree());
            row.put("handoff_revision", item.handoffRevision());
            row.put("handoff_digest", item.handoffDigest());
            row.put("result", item.result());
            row.put("occurred_at", item.occurredAt().toString());
            row.put("contract_version", item.contractVersion());
            row.put("schema_version", item.schemaVersion());
            row.put("quality_gate_version", item.qualityGateVersion());
            row.put("input_digest", item.inputDigest());
            row.put("scenarios", item.scenarios());
            row.put("signature_digest", item.signatureDigest());
            row.put("cleanup_result", item.cleanupResult());
            row.put("runtime_evidence_claim", db(item.runtimeEvidenceClaim()));
            row.put("expires_at", retentionEnd(item.occurredAt()).toString());
            rows.add(row);
        }
        return write(rows);
    }

    private void insert(DataSourceCatalog catalog) {
        jdbc.update("""
                insert into ingestion_quality.iq_data_source_catalog
                  (catalog_id,catalog_release_id,contract_version,status,aggregate_version,
                   content_digest,evidence_set_digest,validation_errors,created_at,updated_at,
                   published_at,expires_at)
                values (?,?,?,?,?,?,?,?::jsonb,?,?,?,?)
                """, catalog.catalogId(), catalog.catalogReleaseId(), catalog.contractVersion(),
                db(catalog.status()), catalog.aggregateVersion(), catalog.contentDigest(),
                catalog.evidenceSetDigest(), write(catalog.validationFailures()),
                Timestamp.from(catalog.createdAt()), Timestamp.from(catalog.updatedAt()),
                timestamp(catalog.publishedAt()), Timestamp.from(retentionEnd(catalog.createdAt())));
        for (SourceContract source : catalog.sources()) {
            Boolean accepted = jdbc.queryForObject("""
                    select ingestion_quality.iq_add_catalog_source(
                      ?,?,?,?,?,?,?,?::jsonb)
                    """, Boolean.class, catalog.catalogId(), source.sourceId(), source.purpose(),
                    source.schemaVersion(), source.qualityGateVersion(), source.evidenceUri(),
                    db(source.runtimeEvidenceClaim()), write(source));
            if (!Boolean.TRUE.equals(accepted)) {
                throw new IllegalStateException("INGESTION_QUALITY_SOURCE_ID_REUSE");
            }
        }
        for (DependencyBinding dependency : catalog.dependencies()) {
            Boolean accepted = jdbc.queryForObject("""
                    select ingestion_quality.iq_add_catalog_dependency(?,?,?,?,?)
                    """, Boolean.class, catalog.catalogId(), dependency.sourceId(),
                    dependency.dependencyId(), db(dependency.requirement()),
                    db(dependency.operator()));
            if (!Boolean.TRUE.equals(accepted)) {
                throw new IllegalStateException("INGESTION_QUALITY_DEPENDENCY_ID_REUSE");
            }
        }
    }

    @Override
    public Optional<CatalogIdempotencyResult> find(String idempotencyKey, Instant now) {
        return jdbc(() -> {
        String keyDigest = sha256(idempotencyKey);
        return jdbc.query("""
                select request_digest,response from ingestion_quality.iq_catalog_idempotency
                 where idempotency_key_digest=? and expires_at>? and response is not null
                """, (row, ignored) -> new CatalogIdempotencyResult(
                    idempotencyKey, row.getString("request_digest"),
                    read(row.getString("response"), CatalogView.class)),
                keyDigest, Timestamp.from(now))
                .stream().findFirst();
        });
    }

    @Override
    public CatalogIdempotencyClaim claim(
            String idempotencyKey, String requestDigest, UUID catalogId, Instant now) {
        return jdbc(() -> {
        String keyDigest = sha256(idempotencyKey);
        int acquired = jdbc.update("""
                insert into ingestion_quality.iq_catalog_idempotency
                  (idempotency_key_digest,request_digest,catalog_id,response,created_at,expires_at)
                values (?,?,?,null,?,?)
                on conflict (idempotency_key_digest) do update
                  set request_digest=excluded.request_digest,catalog_id=excluded.catalog_id,
                      response=null,created_at=excluded.created_at,expires_at=excluded.expires_at
                where ingestion_quality.iq_catalog_idempotency.expires_at<=excluded.created_at
                """, keyDigest, requestDigest, catalogId, Timestamp.from(now),
                Timestamp.from(now.plus(90, ChronoUnit.DAYS)));
        if (acquired == 1) return CatalogIdempotencyClaim.acquired();
        List<ClaimedIdempotency> existing = jdbc.query("""
                select request_digest,response::text response
                  from ingestion_quality.iq_catalog_idempotency
                 where idempotency_key_digest=? and expires_at>?
                """, (row, ignored) -> new ClaimedIdempotency(
                    row.getString("request_digest"), row.getString("response")),
                keyDigest, Timestamp.from(now));
        if (existing.isEmpty()) {
            throw new IllegalStateException("INGESTION_QUALITY_IDEMPOTENCY_CLAIM_UNAVAILABLE");
        }
        ClaimedIdempotency value = existing.getFirst();
        if (!requestDigest.equals(value.requestDigest())) return CatalogIdempotencyClaim.mismatch();
        if (value.response() == null) {
            throw new IllegalStateException("INGESTION_QUALITY_IDEMPOTENCY_CLAIM_INCOMPLETE");
        }
        return CatalogIdempotencyClaim.replay(new CatalogIdempotencyResult(
                idempotencyKey, requestDigest, read(value.response(), CatalogView.class)));
        });
    }

    @Override
    public void complete(CatalogIdempotencyResult result, Instant completedAt) {
        jdbcRun(() -> {
        int updated = jdbc.update("""
                update ingestion_quality.iq_catalog_idempotency
                   set response=?::jsonb
                 where idempotency_key_digest=? and request_digest=? and response is null
                   and expires_at>?
                """, write(result.response()), sha256(result.idempotencyKey()),
                result.requestDigest(), Timestamp.from(completedAt));
        if (updated != 1) {
            throw new IllegalStateException("INGESTION_QUALITY_IDEMPOTENCY_COMPLETE_CONFLICT");
        }
        });
    }

    @Override
    public void append(CatalogAuditEvent event) {
        jdbcRun(() -> {
        TrustedTime recorded = trustedRecordedTime();
        Instant recordedAt = recorded.instant();
        if (recordedAt.isBefore(event.occurredAt())) {
            throw new cn.edu.suda.scholarsense.ingestionquality.application
                    .IngestionQualityApplicationException(
                            "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE");
        }
        AuditTokenizedValue actor = token(
                AuditTokenizationDomain.ACTOR, event.auditActorRef());
        AuditTokenizedValue object = token(
                AuditTokenizationDomain.OBJECT, event.catalogId().toString());
        AuditTokenizedValue sourceIp = token(
                AuditTokenizationDomain.SOURCE_IP, event.sourceIp());
        AuditTokenizedValue aggregate = token(
                AuditTokenizationDomain.AGGREGATE, event.catalogId().toString());
        requireSameProfile(actor, object, sourceIp, aggregate);
        UUID auditId = CatalogUuidV7.generate(recordedAt);
        UUID eventId = CatalogUuidV7.generate(recordedAt);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("decision", "allow");
        context.put("policyVersion", "RFP-1.0.0");
        context.put("scopeCodes", List.of("OWNED_SOURCE"));
        context.put("grantSearchTokens", List.of());
        context.put("notApplicableReason", null);
        LocalAuditFact fact = new LocalAuditFact(
                auditId, "LOCAL-AUDIT-FACT-1.0.0", "ingestion-quality", ActorType.USER,
                actor.value(), List.of("R6"), context,
                event.action(), "data-source-catalog", object.value(),
                "accepted", "INGESTION_QUALITY_AUDITED", "DATA_QUALITY", "OWNED_SOURCE",
                event.occurredAt(), recordedAt, event.timeSourceProfile(),
                sourceIp.value(), actor.profileVersion(), actor.keyVersion(), event.traceId(),
                "data-source-catalog", aggregate.value(), event.aggregateVersion(),
                event.idempotencyKeyDigest(), Map.of("rfp", "RFP-1.0.0"), "RS-1.0.0");
        jdbc.update("""
                insert into ingestion_quality.iq_local_audit_fact
                  (audit_id,actor_search_token,action,result,catalog_id,aggregate_version,
                   trace_id,occurred_at,authorization_context,expires_at)
                values (?,?,?,?,?,?,?,?,?::jsonb,?)
                """, auditId, actor.value(), event.action(), event.result(),
                event.catalogId(), event.aggregateVersion(), event.traceId(),
                Timestamp.from(event.occurredAt()), write(context),
                Timestamp.from(retentionEnd(event.occurredAt())));
        LocalAuditOutboxRecord record = LocalAuditOutboxRecord.forFact(eventId, fact, recordedAt);
        String encoded = write(record);
        jdbc.update("""
                insert into ingestion_quality.iq_local_audit_outbox
                  (event_id,audit_id,event_type,schema_version,producer,payload,payload_digest,available_at,created_at)
                values (?,?,?,?,?,?::jsonb,?,?,?)
                """, eventId, auditId, record.eventType(), record.schemaVersion(), record.producer(),
                encoded, canonicalJsonDigest(encoded),
                Timestamp.from(recordedAt), Timestamp.from(recordedAt));
        });
    }

    private List<SourceContract> sources(UUID catalogId) {
        return jdbc.query("""
                select source_id,purpose,schema_version,quality_gate_version,evidence_uri,
                       runtime_evidence_claim,descriptor::text descriptor
                  from ingestion_quality.iq_source_contract where catalog_id=? order by source_id
                """, (row, ignored) -> new SourceContract(
                    row.getString("source_id"), row.getString("purpose"),
                    row.getString("schema_version"), row.getString("quality_gate_version"),
                    row.getString("evidence_uri"),
                "target-verified".equals(row.getString("runtime_evidence_claim"))
                            ? RuntimeEvidenceClaim.TARGET_VERIFIED : RuntimeEvidenceClaim.NONE,
                    read(row.getString("descriptor"), SourceContract.class).metadata()), catalogId);
    }

    private List<DependencyBinding> dependencies(UUID catalogId) {
        return jdbc.query("""
                select source_id,dependency_id,requirement,combination_operator
                  from ingestion_quality.iq_dependency_binding where catalog_id=? order by dependency_id
                """, (row, ignored) -> new DependencyBinding(
                    row.getString("source_id"), row.getString("dependency_id"),
                    DependencyRequirement.valueOf(row.getString("requirement").toUpperCase(java.util.Locale.ROOT)),
                    DependencyOperator.valueOf(row.getString("combination_operator").replace('-', '_').toUpperCase(java.util.Locale.ROOT))), catalogId);
    }

    private List<CatalogValidationFailure> failures(String encoded) {
        JsonNode root = readTree(encoded);
        List<CatalogValidationFailure> values = new ArrayList<>();
        root.forEach(item -> values.add(new CatalogValidationFailure(
                item.path("code").asText(), item.path("fieldPath").asText())));
        return List.copyOf(values);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException error) { throw new IllegalStateException("INGESTION_QUALITY_JSON_INVALID", error); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JacksonException error) { throw new IllegalStateException("INGESTION_QUALITY_JSON_INVALID", error); }
    }

    private JsonNode readTree(String value) {
        try { return json.readTree(value); }
        catch (JacksonException error) { throw new IllegalStateException("INGESTION_QUALITY_JSON_INVALID", error); }
    }

    private String canonicalJsonDigest(String value) {
        return CanonicalCatalogJson.digest(json, readTree(value)).substring("sha256:".length());
    }

    private static String db(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    private static Instant retentionEnd(Instant occurredAt) {
        return occurredAt.atZone(ZoneOffset.UTC).plusYears(3).toInstant();
    }

    private static void requireVersion(long value, boolean zeroAllowed) {
        long minimum = zeroAllowed ? 0 : 1;
        if (value < minimum || value > DataSourceCatalog.MAX_VERSION) {
            throw new IllegalArgumentException("INGESTION_QUALITY_EXPECTED_VERSION_INVALID");
        }
    }

    private static String sha256(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("INGESTION_QUALITY_DIGEST_INPUT_INVALID");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private AuditTokenizedValue token(
            AuditTokenizationDomain domain, String normalizedValue) {
        AuditTokenizedValue value;
        try {
            value = Objects.requireNonNull(
                    tokenization.tokenize(domain, normalizedValue), "audit token");
        } catch (IllegalArgumentException invariant) {
            throw invariant;
        } catch (RuntimeException unavailable) {
            throw new cn.edu.suda.scholarsense.ingestionquality.application
                    .IngestionQualityApplicationException(
                            "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", unavailable);
        }
        if (!value.value().startsWith(domain.prefix() + "_")) {
            throw new IllegalArgumentException("AUDIT_TOKEN_DOMAIN_MISMATCH");
        }
        return value;
    }

    private TrustedTime trustedRecordedTime() {
        try {
            return Objects.requireNonNull(time.now(), "trusted time");
        } catch (TrustedTimeException unavailable) {
            throw new cn.edu.suda.scholarsense.ingestionquality.application
                    .IngestionQualityApplicationException(
                            "INGESTION_QUALITY_DEPENDENCY_UNAVAILABLE", unavailable);
        }
    }

    private static void requireSameProfile(
            AuditTokenizedValue first, AuditTokenizedValue... rest) {
        for (AuditTokenizedValue value : rest) {
            if (!first.profileVersion().equals(value.profileVersion())
                    || !first.keyVersion().equals(value.keyVersion())) {
                throw new IllegalArgumentException("AUDIT_TOKEN_PROFILE_MISMATCH");
            }
        }
    }

    private <T> T jdbc(Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException failure) {
            throw CatalogJdbcFailures.translate(failure);
        }
    }

    private void jdbcRun(Runnable work) {
        try {
            work.run();
        } catch (DataAccessException failure) {
            throw CatalogJdbcFailures.translate(failure);
        }
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private record ClaimedIdempotency(String requestDigest, String response) {}

}
