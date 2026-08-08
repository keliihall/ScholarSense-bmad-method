package cn.edu.suda.scholarsense.subjectregistry.adapters.outbound;

import cn.edu.suda.scholarsense.subjectregistry.application.SubjectMappingRelayClaim;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectMappingRelayEvent;
import cn.edu.suda.scholarsense.subjectregistry.application.SubjectMappingRelayWorkPort;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Producer-owned relay persistence; it has no access to IQ-owned tables. */
public final class JdbcSubjectMappingEventRelayStore implements SubjectMappingRelayWorkPort {
    private static final Set<String> OUTER_FIELDS = Set.of(
            "specversion", "source", "id", "type", "time", "datacontenttype", "data");
    private static final Set<String> DATA_FIELDS = Set.of(
            "schemaVersion", "contractVersion", "aggregateType", "aggregateId",
            "aggregateVersion", "occurredAt", "producer", "correlationId", "causationId",
            "supersedesId", "reason", "effectiveAt", "lineageId", "traceId",
            "mappingVersion", "relationType", "sourceId", "sourceWatermark",
            "affectedStudentRefs");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcSubjectMappingEventRelayStore(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.transactions = java.util.Objects.requireNonNull(transactions);
        this.json = java.util.Objects.requireNonNull(json);
    }

    @Override
    public List<SubjectMappingRelayClaim> claimDue(
            int batchSize, Instant now, Duration lease) {
        if (batchSize != 100 || !Duration.ofSeconds(60).equals(lease) || now == null) {
            throw new IllegalArgumentException("SUBJECT_MAPPING_RELAY_POLICY_INVALID");
        }
        List<SubjectMappingRelayClaim> claims = transactions.execute(status -> {
            List<Candidate> candidates = jdbc.query("""
                    select request_id, correction_lineage_id, source_id, source_watermark,
                           trace_id, affected_student_ref_count, payload::text,
                           created_at, attempts
                      from subject_registry.sr_mapping_recompute_outbox
                     where status in ('pending','retrying') and available_at<=?
                       and (claimed_until is null or claimed_until<?)
                     order by created_at,request_id
                     for update skip locked limit ?
                    """, (row, ignored) -> new Candidate(
                            row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                            row.getString(3), row.getString(4), row.getString(5),
                            row.getInt(6), row.getString(7), row.getTimestamp(8).toInstant(),
                            row.getLong(9)), Timestamp.from(now), Timestamp.from(now), batchSize);
            List<SubjectMappingRelayClaim> result = new ArrayList<>();
            for (Candidate candidate : candidates) {
                long attempts = Math.addExact(candidate.attempts(), 1);
                SubjectMappingRelayEvent event = verified(candidate);
                if (event == null) {
                    mutateIntegrityFailure(candidate, attempts, now);
                    continue;
                }
                int changed = jdbc.update("""
                        update subject_registry.sr_mapping_recompute_outbox
                           set status='retrying',attempts=?,claimed_until=?,last_error_code=null
                         where request_id=? and attempts=? and status in ('pending','retrying')
                        """, attempts, Timestamp.from(now.plus(lease)),
                        candidate.requestId(), candidate.attempts());
                if (changed != 1) throw new IllegalStateException("SUBJECT_MAPPING_RELAY_FENCED");
                result.add(new SubjectMappingRelayClaim(event, attempts));
            }
            return List.copyOf(result);
        });
        return claims == null ? List.of() : claims;
    }

    private SubjectMappingRelayEvent verified(Candidate candidate) {
        try {
            JsonNode event = json.readTree(candidate.payload());
            if (!fields(event).equals(OUTER_FIELDS)
                    || !"1.0".equals(event.path("specversion").asText())
                    || !"urn:scholarsense:subject-registry".equals(event.path("source").asText())
                    || !candidate.requestId().toString().equals(event.path("id").asText())
                    || !"cn.edu.suda.scholarsense.subject-mapping.changed.v1".equals(
                            event.path("type").asText())
                    || !"application/json".equals(event.path("datacontenttype").asText())) {
                return null;
            }
            JsonNode data = event.path("data");
            if (!fields(data).equals(DATA_FIELDS)
                    || !"SUBJECT-MAPPING-CHANGED-1.0.0".equals(
                            data.path("schemaVersion").asText())
                    || !"SUBJECT-REGISTRY-1.0.0".equals(data.path("contractVersion").asText())
                    || !"SubjectMapping".equals(data.path("aggregateType").asText())
                    || !candidate.correctionLineageId().toString().equals(
                            data.path("aggregateId").asText())
                    || data.path("aggregateVersion").asLong() != 1
                    || !candidate.correctionLineageId().toString().equals(
                            data.path("lineageId").asText())
                    || !candidate.sourceId().equals(data.path("sourceId").asText())
                    || !candidate.sourceWatermark().equals(data.path("sourceWatermark").asText())
                    || !candidate.traceId().equals(data.path("traceId").asText())) {
                return null;
            }
            Instant outerTime = Instant.parse(event.path("time").asText());
            Instant occurredAt = Instant.parse(data.path("occurredAt").asText());
            if (!outerTime.equals(occurredAt)) return null;
            LinkedHashSet<String> affected = new LinkedHashSet<>();
            data.path("affectedStudentRefs").forEach(item -> affected.add(item.asText()));
            if (affected.size() != candidate.affectedCount()) return null;
            return new SubjectMappingRelayEvent(
                    candidate.requestId(), candidate.correctionLineageId(), 1, occurredAt,
                    candidate.correctionLineageId(), candidate.sourceId(), affected,
                    candidate.sourceWatermark(), candidate.traceId());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Set<String> fields(JsonNode node) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        return Set.copyOf(names);
    }

    private void mutateIntegrityFailure(Candidate candidate, long attempts, Instant now) {
        int changed = jdbc.update("""
                update subject_registry.sr_mapping_recompute_outbox
                   set status='failed',attempts=?,available_at=?,claimed_until=null,
                       delivered_at=null,last_error_code='SUBJECT_MAPPING_EVENT_INTEGRITY_INVALID'
                 where request_id=? and attempts=? and status in ('pending','retrying')
                """, attempts, Timestamp.from(now), candidate.requestId(), candidate.attempts());
        if (changed != 1) throw new IllegalStateException("SUBJECT_MAPPING_RELAY_FENCED");
    }

    @Override public boolean confirm(UUID id, long attempts, Instant at) {
        return mutate("status='delivered',delivered_at=?,claimed_until=null,last_error_code=null",
                id, attempts, at, null);
    }

    @Override public boolean retry(UUID id, long attempts, Instant at, String code) {
        return mutate("status='retrying',available_at=?,claimed_until=null,last_error_code=?",
                id, attempts, at, code);
    }

    @Override public boolean fail(UUID id, long attempts, Instant at, String code) {
        return mutate("status='failed',available_at=?,claimed_until=null,last_error_code=?",
                id, attempts, at, code);
    }

    private boolean mutate(String assignment, UUID id, long attempts, Instant at, String code) {
        if (code != null && !code.matches("^SUBJECT_MAPPING_[A-Z0-9_]{2,110}$")) {
            throw new IllegalArgumentException("SUBJECT_MAPPING_RELAY_ERROR_CODE_INVALID");
        }
        Integer changed = transactions.execute(status -> code == null
                ? jdbc.update("update subject_registry.sr_mapping_recompute_outbox set "
                        + assignment + " where request_id=? and attempts=? and status='retrying'",
                        Timestamp.from(at), id, attempts)
                : jdbc.update("update subject_registry.sr_mapping_recompute_outbox set "
                        + assignment + " where request_id=? and attempts=? and status='retrying'",
                        Timestamp.from(at), code, id, attempts));
        return changed != null && changed == 1;
    }

    private record Candidate(
            UUID requestId, UUID correctionLineageId, String sourceId,
            String sourceWatermark, String traceId, int affectedCount,
            String payload, Instant createdAt, long attempts) {}
}
