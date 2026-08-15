package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.HistoricalWindowPort;
import cn.edu.suda.scholarsense.ingestionquality.application.MappingRecomputeJobPort;
import cn.edu.suda.scholarsense.ingestionquality.application.MappingRecomputeCompletion;
import cn.edu.suda.scholarsense.ingestionquality.application.MappingRecomputePlanPort;
import cn.edu.suda.scholarsense.ingestionquality.application.RecomputeJobQueryPort;
import cn.edu.suda.scholarsense.ingestionquality.application.RecomputeJobRecord;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectMappingChangedFact;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectMappingEventOutcome;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectMappingConsumerReconciliationPort;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectMappingEventApplyPort;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectWindowRecomputeCandidate;
import cn.edu.suda.scholarsense.ingestionquality.application.SubjectWindowRecomputeWorkPort;
import cn.edu.suda.scholarsense.ingestionquality.domain.HistoricalWindow;
import cn.edu.suda.scholarsense.ingestionquality.domain.MappingRecomputeIdentity;
import cn.edu.suda.scholarsense.ingestionquality.domain.MappingRecomputeJob;
import cn.edu.suda.scholarsense.ingestionquality.domain.MappingRecomputeJobStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.MappingRecomputeResultCode;
import cn.edu.suda.scholarsense.shared.observability.CurrentTraceSource;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContext;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContextCodec;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** IQ-owned persistence for reconstructable windows, mapping events and recompute jobs. */
public final class JdbcSubjectWindowRecomputeStore implements
        HistoricalWindowPort, MappingRecomputeJobPort, MappingRecomputePlanPort, RecomputeJobQueryPort,
        SubjectMappingEventApplyPort, SubjectMappingConsumerReconciliationPort,
        SubjectWindowRecomputeWorkPort {
    private static final String CONSUMER_ID = "ingestion-quality-subject-window";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentTraceSource currentTrace;
    private final W3cTraceContextCodec traceCodec;

    public JdbcSubjectWindowRecomputeStore(JdbcTemplate jdbc, ObjectMapper json) {
        this(jdbc, json, Optional::empty, new W3cTraceContextCodec());
    }

    public JdbcSubjectWindowRecomputeStore(
            JdbcTemplate jdbc,
            ObjectMapper json,
            CurrentTraceSource currentTrace,
            W3cTraceContextCodec traceCodec) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.json = java.util.Objects.requireNonNull(json);
        this.currentTrace = java.util.Objects.requireNonNull(currentTrace);
        this.traceCodec = java.util.Objects.requireNonNull(traceCodec);
    }

    public boolean recordWindow(HistoricalWindow window, Instant serverNow) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select ingestion_quality.iq_record_historical_window(
                  ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb,
                  ?, ?, ?, ?, ?, ?, ?)
                """, Boolean.class,
                window.windowId(), UUID.fromString(window.subjectRef()),
                Timestamp.from(window.startAt()), Timestamp.from(window.endAt()),
                window.timezone().getId(), json.writeValueAsString(window.sourceVersions()),
                json.writeValueAsString(window.sourceWatermarks()), window.mappingVersion(),
                json.writeValueAsString(window.qualityGateVersions()), window.ruleId(),
                window.ruleVersion(), window.scenarioId(), window.lineageRunId(),
                window.inputDigest(), Timestamp.from(window.latestActionableAt()),
                Timestamp.from(serverNow)));
    }

    @Override
    public List<HistoricalWindow> findBySubjectRefs(Set<String> subjectRefs) {
        if (subjectRefs == null || subjectRefs.isEmpty()) return List.of();
        UUID[] values = subjectRefs.stream().map(UUID::fromString).toArray(UUID[]::new);
        return jdbc.query("""
                select subject_ref, window_id, start_at, end_at, timezone,
                       source_versions::text, source_watermarks::text, mapping_version,
                       quality_gate_versions::text, rule_id, rule_version, scenario_id,
                       lineage_run_id, input_digest, latest_actionable_at
                  from ingestion_quality.iq_historical_window
                 where subject_ref=any(?::uuid[])
                 order by subject_ref, start_at, window_id
                """, (row, ignored) -> new HistoricalWindow(
                        row.getObject(1, UUID.class).toString(), row.getString(2),
                        row.getTimestamp(3).toInstant(), row.getTimestamp(4).toInstant(),
                        ZoneId.of(row.getString(5)), longMap(row.getString(6)),
                        stringMap(row.getString(7)), row.getLong(8),
                        stringList(row.getString(9)), row.getString(10), row.getString(11),
                        row.getString(12), row.getObject(13, UUID.class), row.getString(14).trim(),
                        row.getTimestamp(15).toInstant()), (Object) values);
    }

    @Override
    public Optional<MappingRecomputeJob> findByIdentity(MappingRecomputeIdentity identity) {
        return queryJobs("""
                where correction_lineage_id=? and student_ref=? and rule_id=?
                  and rule_version=? and scenario_id=? and window_id=?
                  and input_watermarks_digest=?
                """, identity.correctionLineageId(), UUID.fromString(identity.studentRef()),
                identity.ruleId(), identity.ruleVersion(), identity.scenarioId(),
                identity.windowId(), identity.inputWatermarksDigest()).stream().findFirst();
    }

    @Override
    public MappingRecomputeJob insertIfAbsent(MappingRecomputeJob job) {
        W3cTraceContext enqueueContext = currentTrace.current()
                .filter(context -> job.traceId().equals(context.traceId()))
                .orElseGet(() -> traceCodec.resume(job.traceId(), false));
        UUID persisted = jdbc.queryForObject("""
                select ingestion_quality.iq_enqueue_mapping_recompute_v2(
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.class, job.jobId(), job.identity().correctionLineageId(),
                job.ownerSourceId(), UUID.fromString(job.identity().studentRef()), job.identity().ruleId(),
                job.identity().ruleVersion(), job.identity().scenarioId(),
                job.identity().windowId(), job.identity().inputWatermarksDigest(),
                Timestamp.from(job.latestActionableAt()), Timestamp.from(job.queuedAt()),
                job.traceId(), traceCodec.format(enqueueContext));
        if (persisted == null) {
            throw new IllegalStateException("INGESTION_QUALITY_NO_ACTIONABLE_WINDOW");
        }
        return findByIdentity(job.identity()).orElseThrow();
    }

    @Override
    public void recordPlan(
            UUID requestId, UUID correctionLineageId, String ownerSourceId,
            int jobCount, int historyOnlyWindowCount, Instant plannedAt, String traceId) {
        jdbc.queryForObject("""
                select ingestion_quality.iq_record_mapping_recompute_plan(
                  ?, ?, ?, ?, ?, ?, ?)
                """, Boolean.class, requestId, correctionLineageId, ownerSourceId,
                jobCount, historyOnlyWindowCount, Timestamp.from(plannedAt), traceId);
    }

    @Override
    public List<SubjectWindowRecomputeCandidate> findClaimable(
            int batchSize, Instant serverNow) {
        if (batchSize != 100 || serverNow == null) {
            throw new IllegalArgumentException("INGESTION_QUALITY_RECOMPUTE_POLICY_INVALID");
        }
        return jdbc.query("""
                select job_id, checkpoint_sequence, trace_id, traceparent
                  from ingestion_quality.iq_mapping_recompute_job
                 where status='queued'
                    or (status='running' and lease_until<=?)
                 order by queued_at,job_id
                 limit ?
                """, (row, ignored) -> new SubjectWindowRecomputeCandidate(
                        row.getObject(1, UUID.class), row.getLong(2), row.getString(3),
                        row.getString(4)),
                Timestamp.from(serverNow), batchSize);
    }

    @Override
    public long claim(UUID jobId, String workerId, Instant serverNow, java.time.Duration lease) {
        if (lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("INGESTION_QUALITY_LEASE_INVALID");
        }
        return jdbc.queryForObject("""
                select ingestion_quality.iq_claim_mapping_recompute_job(?, ?, ?, ?)
                """, Long.class, jobId, workerId, Timestamp.from(serverNow),
                Timestamp.from(serverNow.plus(lease)));
    }

    public boolean checkpoint(UUID jobId, long fencingToken, long sequence, Instant serverNow) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select ingestion_quality.iq_checkpoint_mapping_recompute_job(?, ?, ?, ?)
                """, Boolean.class, jobId, fencingToken, sequence, Timestamp.from(serverNow)));
    }

    @Override
    public MappingRecomputeCompletion complete(
            UUID jobId, long fencingToken, Instant serverNow, UUID eventId) {
        String value = jdbc.queryForObject("""
                select ingestion_quality.iq_complete_mapping_recompute_job(?, ?, ?, ?)::text
                """, String.class, jobId, fencingToken, Timestamp.from(serverNow), eventId);
        try {
            JsonNode node = json.readTree(value);
            return new MappingRecomputeCompletion(
                    node.get("resultCode").asText(), node.get("historyCorrected").asBoolean(),
                    node.get("businessPublicationCreated").asBoolean());
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new IllegalStateException("INGESTION_QUALITY_RESULT_INVALID", invalid);
        }
    }

    @Override
    public boolean fail(UUID jobId, long fencingToken, Instant serverNow, String controlledCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select ingestion_quality.iq_fail_mapping_recompute_job(?, ?, ?, ?)
                """, Boolean.class, jobId, fencingToken, Timestamp.from(serverNow), controlledCode));
    }

    @Override
    public SubjectMappingEventOutcome accept(
            SubjectMappingChangedFact fact, boolean backfill, Instant receivedAt) {
        String outcome = jdbc.queryForObject("""
                select ingestion_quality.iq_accept_subject_mapping_event(
                  ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid[], ?, ?, ?, ?)
                """, String.class, CONSUMER_ID, fact.source(), fact.eventId(),
                fact.aggregateId(), fact.aggregateVersion(), Timestamp.from(fact.occurredAt()),
                fact.correctionLineageId(), fact.sourceId(),
                fact.affectedStudentRefs().stream().map(UUID::fromString).toArray(UUID[]::new),
                fact.inputWatermark(), backfill ? "backfill" : "normal", fact.schemaValid(),
                Timestamp.from(receivedAt));
        return SubjectMappingEventOutcome.valueOf(outcome);
    }

    @Override
    public boolean reconcile(UUID aggregateId, long authoritativeVersion, Instant serverNow) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select ingestion_quality.iq_reconcile_subject_mapping_consumer(?, ?, ?, ?)
                """, Boolean.class, CONSUMER_ID, aggregateId, authoritativeVersion,
                Timestamp.from(serverNow)));
    }

    @Override
    public Optional<RecomputeJobRecord> findJob(UUID jobId) {
        List<RecomputeJobRecord> rows = jdbc.query("""
                select job.job_id, job.status, job.attempt_no, job.queued_at,
                       job.completed_at, job.result_code, job.trace_id,
                       job.owner_source_id, job.object_version
                  from ingestion_quality.iq_mapping_recompute_job job
                 where job.job_id=?
                """, (row, ignored) -> new RecomputeJobRecord(
                        row.getObject(1, UUID.class), status(row.getString(2)), row.getInt(3),
                        row.getTimestamp(4).toInstant(),
                        row.getTimestamp(5) == null ? null : row.getTimestamp(5).toInstant(),
                        row.getString(6), row.getString(7), row.getString(8),
                        row.getLong(9)), jobId);
        if (!rows.isEmpty()) return Optional.of(rows.getFirst());
        List<RecomputeJobRecord> requests = jdbc.query("""
                select request.request_id,
                       case
                         when request.job_count=0 then 'succeeded'
                         when count(job.job_id) < request.job_count then 'queued'
                         when bool_or(job.status='failed') then 'failed'
                         when bool_or(job.status='running') then 'running'
                         when bool_or(job.status='queued') then 'queued'
                         when bool_or(job.status='cancelled') then 'cancelled'
                         else 'succeeded'
                       end as aggregate_status,
                       coalesce(max(job.attempt_no), 0),
                       request.planned_at,
                       case
                         when request.job_count=0 then request.planned_at
                         when count(job.job_id)=request.job_count
                              and bool_and(job.status in ('succeeded','failed','cancelled'))
                           then max(job.completed_at)
                         else null
                       end as completed_at,
                       case
                         when request.job_count=0 then 'NO_ACTIONABLE_WINDOW'
                         when count(job.job_id)=request.job_count
                              and bool_and(job.status='succeeded')
                           then case when bool_or(job.result_code='RECOMPUTED')
                                then 'RECOMPUTED' else 'EXPIRED_HISTORY_ONLY' end
                         else null
                       end as result_code,
                       request.trace_id,
                       request.owner_source_id,
                       request.object_version + coalesce(sum(job.object_version), 0)
                  from ingestion_quality.iq_mapping_recompute_request request
                  left join ingestion_quality.iq_mapping_recompute_job job
                    on job.correction_lineage_id=request.correction_lineage_id
                   and job.owner_source_id=request.owner_source_id
                 where request.request_id=?
                 group by request.request_id, request.job_count, request.planned_at,
                          request.trace_id, request.owner_source_id, request.object_version
                """, (row, ignored) -> new RecomputeJobRecord(
                        row.getObject(1, UUID.class), status(row.getString(2)), row.getInt(3),
                        row.getTimestamp(4).toInstant(),
                        row.getTimestamp(5) == null ? null : row.getTimestamp(5).toInstant(),
                        row.getString(6), row.getString(7), row.getString(8),
                        row.getLong(9)), jobId);
        return requests.stream().findFirst();
    }

    private List<MappingRecomputeJob> queryJobs(String suffix, Object... arguments) {
        return jdbc.query("""
                select job_id, owner_source_id, correction_lineage_id, student_ref, rule_id, rule_version,
                       scenario_id, window_id, input_watermarks_digest,
                       latest_actionable_at, queued_at, trace_id, status, attempt_no,
                       fencing_token, lease_owner, lease_until, checkpoint_sequence,
                       history_corrected, business_publication_created, result_code,
                       failure_code, completed_at
                  from ingestion_quality.iq_mapping_recompute_job
                """ + suffix, (row, ignored) -> MappingRecomputeJob.restore(
                        row.getObject(1, UUID.class), row.getString(2), new MappingRecomputeIdentity(
                                row.getObject(3, UUID.class), row.getObject(4, UUID.class).toString(),
                                row.getString(5), row.getString(6), row.getString(7), row.getString(8),
                                row.getString(9).trim()), row.getTimestamp(10).toInstant(),
                        row.getTimestamp(11).toInstant(), row.getString(12), status(row.getString(13)),
                        row.getInt(14), row.getLong(15), row.getString(16),
                        row.getTimestamp(17) == null ? null : row.getTimestamp(17).toInstant(),
                        row.getLong(18), row.getBoolean(19), row.getBoolean(20),
                        resultCode(row.getString(21)), row.getString(22),
                        row.getTimestamp(23) == null ? null : row.getTimestamp(23).toInstant()),
                arguments);
    }

    private Map<String, Long> longMap(String value) {
        try {
            JsonNode node = json.readTree(value);
            Map<String, Long> result = new LinkedHashMap<>();
            node.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asLong()));
            return Map.copyOf(result);
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private Map<String, String> stringMap(String value) {
        try {
            JsonNode node = json.readTree(value);
            Map<String, String> result = new LinkedHashMap<>();
            node.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asText()));
            return Map.copyOf(result);
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private List<String> stringList(String value) {
        try {
            JsonNode node = json.readTree(value);
            ArrayList<String> result = new ArrayList<>();
            node.forEach(item -> result.add(item.asText()));
            return List.copyOf(result);
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private static MappingRecomputeJobStatus status(String value) {
        return MappingRecomputeJobStatus.valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }

    private static MappingRecomputeResultCode resultCode(String value) {
        return value == null ? null : MappingRecomputeResultCode.valueOf(value);
    }

}
