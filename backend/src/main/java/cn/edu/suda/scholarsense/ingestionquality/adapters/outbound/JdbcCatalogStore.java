package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditEvent;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogAuditPort;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogIdempotencyPort;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogIdempotencyResult;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogRepository;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogVersionConflictException;
import cn.edu.suda.scholarsense.ingestionquality.application.CatalogView;
import cn.edu.suda.scholarsense.ingestionquality.domain.CatalogStatus;
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
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** JDBC owner adapter. Callers wrap every command in JdbcCatalogTransactionAdapter. */
public final class JdbcCatalogStore
        implements CatalogRepository, CatalogIdempotencyPort, CatalogAuditPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcCatalogStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public Optional<DataSourceCatalog> find(UUID catalogId) {
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
    }

    @Override
    public List<DataSourceCatalog> list(int offset, int limit) {
        return jdbc.query("""
                select catalog_id from ingestion_quality.iq_data_source_catalog
                 order by updated_at desc,catalog_id limit ? offset ?
                """, (row, ignored) -> row.getObject(1, UUID.class), limit, offset).stream()
                .map(this::find).flatMap(Optional::stream).toList();
    }

    @Override
    public Optional<DataSourceCatalog> current() {
        List<UUID> ids = jdbc.query("""
                select catalog_id from ingestion_quality.iq_catalog_current where singleton=true
                """, (row, ignored) -> row.getObject(1, UUID.class));
        return ids.stream().findFirst().flatMap(this::find);
    }

    @Override
    public void save(DataSourceCatalog catalog, long expectedVersion) {
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
        if (catalog.status() == CatalogStatus.INVALID || catalog.status() == CatalogStatus.PUBLISHABLE) {
            jdbc.update("""
                    insert into ingestion_quality.iq_catalog_validation_attempt
                      (attempt_id,catalog_id,aggregate_version,result,errors,validated_at,trace_id)
                    values (?,?,?,?,?::jsonb,?,?)
                    """,
                    CatalogUuidV7.generate(catalog.updatedAt()), catalog.catalogId(),
                    catalog.aggregateVersion(), db(catalog.status()),
                    write(catalog.validationFailures()), Timestamp.from(catalog.updatedAt()),
                    "trace-not-recorded-by-repository");
        }
        if (catalog.status() == CatalogStatus.PUBLISHED) {
            jdbc.update("""
                    insert into ingestion_quality.iq_catalog_current
                      (singleton,catalog_id,aggregate_version,switched_at)
                    values (true,?,?,?)
                    on conflict (singleton) do update
                      set catalog_id=excluded.catalog_id,
                          aggregate_version=excluded.aggregate_version,
                          switched_at=excluded.switched_at
                    """, catalog.catalogId(), catalog.aggregateVersion(), Timestamp.from(catalog.publishedAt()));
        }
    }

    private void insert(DataSourceCatalog catalog) {
        jdbc.update("""
                insert into ingestion_quality.iq_data_source_catalog
                  (catalog_id,catalog_release_id,contract_version,status,aggregate_version,
                   content_digest,evidence_set_digest,validation_errors,created_at,updated_at,published_at)
                values (?,?,?,?,?,?,?,?::jsonb,?,?,?)
                """, catalog.catalogId(), catalog.catalogReleaseId(), catalog.contractVersion(),
                db(catalog.status()), catalog.aggregateVersion(), catalog.contentDigest(),
                catalog.evidenceSetDigest(), write(catalog.validationFailures()),
                Timestamp.from(catalog.createdAt()), Timestamp.from(catalog.updatedAt()),
                timestamp(catalog.publishedAt()));
        for (SourceContract source : catalog.sources()) {
            jdbc.update("""
                    insert into ingestion_quality.iq_source_id_reservation
                      (source_id,first_catalog_id,first_purpose,reserved_at)
                    values (?,?,?,?) on conflict (source_id) do nothing
                    """, source.sourceId(), catalog.catalogId(), source.purpose(),
                    Timestamp.from(catalog.createdAt()));
            String firstPurpose = jdbc.queryForObject("""
                    select first_purpose from ingestion_quality.iq_source_id_reservation
                     where source_id=?
                    """, String.class, source.sourceId());
            if (!source.purpose().equals(firstPurpose)) {
                throw new IllegalStateException("INGESTION_QUALITY_SOURCE_ID_REUSE");
            }
            jdbc.update("""
                    insert into ingestion_quality.iq_source_contract
                      (catalog_id,source_id,purpose,schema_version,quality_gate_version,
                       evidence_uri,runtime_evidence_claim,descriptor)
                    values (?,?,?,?,?,?,?,?::jsonb)
                    """, catalog.catalogId(), source.sourceId(), source.purpose(),
                    source.schemaVersion(), source.qualityGateVersion(), source.evidenceUri(),
                    db(source.runtimeEvidenceClaim()), write(source));
        }
        for (DependencyBinding dependency : catalog.dependencies()) {
            jdbc.update("""
                    insert into ingestion_quality.iq_dependency_id_reservation
                      (dependency_id,source_id,first_catalog_id,reserved_at)
                    values (?,?,?,?) on conflict (dependency_id) do nothing
                    """, dependency.dependencyId(), dependency.sourceId(), catalog.catalogId(),
                    Timestamp.from(catalog.createdAt()));
            String boundSource = jdbc.queryForObject("""
                    select source_id from ingestion_quality.iq_dependency_id_reservation
                     where dependency_id=?
                    """, String.class, dependency.dependencyId());
            if (!dependency.sourceId().equals(boundSource)) {
                throw new IllegalStateException("INGESTION_QUALITY_DEPENDENCY_ID_REUSE");
            }
            jdbc.update("""
                    insert into ingestion_quality.iq_dependency_binding
                      (catalog_id,source_id,dependency_id,requirement,combination_operator)
                    values (?,?,?,?,?)
                    """, catalog.catalogId(), dependency.sourceId(), dependency.dependencyId(),
                    db(dependency.requirement()), db(dependency.operator()));
        }
    }

    @Override
    public Optional<CatalogIdempotencyResult> find(String idempotencyKey) {
        String keyDigest = sha256(idempotencyKey);
        return jdbc.query("""
                select request_digest,response from ingestion_quality.iq_catalog_idempotency
                 where idempotency_key_digest=? and expires_at>current_timestamp
                """, (row, ignored) -> new CatalogIdempotencyResult(
                    idempotencyKey, row.getString("request_digest"),
                    read(row.getString("response"), CatalogView.class)), keyDigest)
                .stream().findFirst();
    }

    @Override
    public void save(CatalogIdempotencyResult result) {
        Instant createdAt = result.response().updatedAt();
        jdbc.update("""
                insert into ingestion_quality.iq_catalog_idempotency
                  (idempotency_key_digest,request_digest,catalog_id,response,created_at,expires_at)
                values (?,?,?,?::jsonb,?,?)
                """, sha256(result.idempotencyKey()), result.requestDigest(),
                result.response().catalogId(), write(result.response()), Timestamp.from(createdAt),
                Timestamp.from(createdAt.plus(90, ChronoUnit.DAYS)));
    }

    @Override
    public void append(CatalogAuditEvent event) {
        UUID auditId = CatalogUuidV7.generate(event.occurredAt());
        UUID eventId = CatalogUuidV7.generate(event.occurredAt());
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("decision", "allow");
        context.put("policyVersion", "RFP-1.0.0");
        context.put("scopeCodes", List.of("OWNED_SOURCE"));
        context.put("grantSearchTokens", List.of());
        context.put("notApplicableReason", null);
        LocalAuditFact fact = new LocalAuditFact(
                auditId, "LOCAL-AUDIT-FACT-1.0.0", "ingestion-quality", ActorType.USER,
                searchToken("ast", event.actorRef()), List.of("R6"), context,
                event.action(), "data-source-catalog", searchToken("ost", event.catalogId().toString()),
                "accepted", "INGESTION_QUALITY_AUDITED", "DATA_QUALITY", "OWNED_SOURCE",
                event.occurredAt(), event.occurredAt(), new TimeSourceProfile(
                        "system-clock", "AUDIT-CLOCK-BINDING-1.0.0", 0, event.occurredAt(),
                        event.occurredAt().plusSeconds(300), "evidence://signed/ingestion-quality/system-clock"),
                null, "AUDIT-TOKENIZATION-1.0.0", "k1", event.traceId(),
                "data-source-catalog", searchToken("agt", event.catalogId().toString()),
                event.aggregateVersion(), null, Map.of("rfp", "RFP-1.0.0"), "RS-1.0.0");
        jdbc.update("""
                insert into ingestion_quality.iq_local_audit_fact
                  (audit_id,actor_search_token,action,result,catalog_id,aggregate_version,
                   trace_id,occurred_at,authorization_context)
                values (?,?,?,?,?,?,?,?,?::jsonb)
                """, auditId, sha256(event.actorRef()), event.action(), event.result(),
                event.catalogId(), event.aggregateVersion(), event.traceId(),
                Timestamp.from(event.occurredAt()), write(context));
        LocalAuditOutboxRecord record = LocalAuditOutboxRecord.forFact(eventId, fact, event.occurredAt());
        String encoded = write(record);
        jdbc.update("""
                insert into ingestion_quality.iq_local_audit_outbox
                  (event_id,audit_id,event_type,schema_version,producer,payload,payload_digest,available_at,created_at)
                values (?,?,?,?,?,?::jsonb,?,?,?)
                """, eventId, auditId, record.eventType(), record.schemaVersion(), record.producer(),
                encoded, sha256(encoded),
                Timestamp.from(event.occurredAt()), Timestamp.from(event.occurredAt()));
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

    private static String db(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
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

    private static String searchToken(String prefix, String value) {
        return prefix + "_v1_k1_" + sha256(value);
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
}
