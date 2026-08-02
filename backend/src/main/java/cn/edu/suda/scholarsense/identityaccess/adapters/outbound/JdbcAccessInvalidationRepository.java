package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationAppendCommand;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationImpactJobPort;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationStorePort;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationCause;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAggregateType;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationSnapshot;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationAuthorizationState;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationChangeKind;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationDependencyWatermark;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageHead;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationLineageId;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationReason;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationRetention;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSourceVector;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationSubjectSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Focused identity-owned invalidation persistence. Fact, head, outbox, and
 * propagation rows are committed as one transaction.
 */
public final class JdbcAccessInvalidationRepository
        implements AccessInvalidationStorePort, AccessInvalidationImpactJobPort {
    private static final String EVENT_TYPE =
            "scholarsense.identity-access.responsibility.changed.v1";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final AccessInvalidationEventJsonCodec events;

    public JdbcAccessInvalidationRepository(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.events = new AccessInvalidationEventJsonCodec(json);
    }

    @Override
    public AccessInvalidationFact append(
            AccessInvalidationAppendCommand command) {
        return transactions.execute(status -> appendInTransaction(command));
    }

    @Override
    public Optional<AccessInvalidationLineageHead> head(
            String lineageId) {
        return jdbc.query("""
                select lineage_id, current_event_id, current_version
                  from identity_access.ia_access_invalidation_lineage_head
                 where lineage_id=?
                """,
                (rs, row) -> new AccessInvalidationLineageHead(
                        new AccessInvalidationLineageId(
                                rs.getString("lineage_id")),
                        rs.getObject("current_event_id", UUID.class),
                        rs.getLong("current_version")),
                lineageId)
                .stream()
                .findFirst();
    }

    @Override
    public long fencingToken(String lineageId) {
        List<Long> values = jdbc.queryForList("""
                select fencing_token
                  from identity_access.ia_access_invalidation_lineage_head
                 where lineage_id=?
                """, Long.class, lineageId);
        return values.stream().findFirst().orElse(0L);
    }

    @Override
    public Optional<AccessInvalidationFact> find(UUID eventId) {
        return facts("""
                where fact.event_id=?
                """, eventId).stream().findFirst();
    }

    @Override
    public Optional<AccessInvalidationFact> latest(String lineageId) {
        return facts("""
                join identity_access
                  .ia_access_invalidation_lineage_head head
                  on head.current_event_id=fact.event_id
                where head.lineage_id=?
                """, lineageId).stream().findFirst();
    }

    @Override
    public void enqueue(
            UUID jobId,
            AccessInvalidationCause cause,
            java.time.Instant retainUntil) {
        jdbc.update("""
                insert into identity_access.ia_access_invalidation_job (
                  job_id, job_kind, lineage_id, cause_event_id, status,
                  due_at, fencing_token, cursor_value, attempts,
                  next_attempt_at, reason_code, trace_id, created_at,
                  updated_at, retain_until)
                values (
                  ?, 'impact', ?, ?, 'pending', ?, 0, 0, 0, ?, ?, ?, ?, ?, ?)
                on conflict (job_id) do nothing
                """,
                jobId,
                cause.causeLineageId().value(),
                cause.causeEventId(),
                Timestamp.from(cause.effectiveAt()),
                Timestamp.from(cause.effectiveAt()),
                cause.reasonCode().name(),
                cause.traceId(),
                Timestamp.from(cause.effectiveAt()),
                Timestamp.from(cause.effectiveAt()),
                Timestamp.from(retainUntil));
        Boolean bindingMatches = jdbc.queryForObject("""
                select job_kind='impact'
                       and lineage_id=?
                       and cause_event_id=?
                       and due_at=?
                       and reason_code=?
                       and trace_id=?
                       and created_at=?
                       and retain_until=?
                  from identity_access.ia_access_invalidation_job
                 where job_id=?
                """,
                Boolean.class,
                cause.causeLineageId().value(),
                cause.causeEventId(),
                Timestamp.from(cause.effectiveAt()),
                cause.reasonCode().name(),
                cause.traceId(),
                Timestamp.from(cause.effectiveAt()),
                Timestamp.from(retainUntil),
                jobId);
        if (!Boolean.TRUE.equals(bindingMatches)) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_IMPACT_IDEMPOTENCY_CONFLICT");
        }
    }

    private AccessInvalidationFact appendInTransaction(
            AccessInvalidationAppendCommand command) {
        AccessInvalidationFact fact = command.fact();
        var validated = events.validate(fact, command.eventPayload());
        List<ExistingDigest> existing = jdbc.query("""
                select payload_digest, source_payload_digest
                  from identity_access.ia_access_invalidation_fact
                 where event_id=?
                """,
                (rs, row) -> new ExistingDigest(
                        rs.getString("payload_digest"),
                        rs.getString("source_payload_digest")),
                fact.eventId());
        if (!existing.isEmpty()) {
            if (existing.getFirst().eventPayloadDigest()
                            .equals(validated.payloadDigest())
                    && existing.getFirst().sourcePayloadDigest()
                            .equals(fact.payloadDigest())) {
                return fact;
            }
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_IDEMPOTENCY_CONFLICT");
        }
        if (fact.aggregateVersion() == 1) {
            appendRoot(command, validated);
        } else {
            appendSuccessor(command, validated);
        }
        return fact;
    }

    private void appendRoot(
            AccessInvalidationAppendCommand command,
            AccessInvalidationEventJsonCodec.ValidatedPayload validated) {
        if (command.expectedHeadVersion() != 0
                || command.expectedFencingToken() != 0
                || command.fact().supersedesId() != null) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_ROOT_HEAD_INVALID");
        }
        insertFactAndOutbox(command, validated);
        AccessInvalidationFact fact = command.fact();
        int inserted = jdbc.update("""
                insert into identity_access.ia_access_invalidation_lineage_head (
                  lineage_id, aggregate_type, aggregate_id, current_event_id,
                  current_version, fencing_token, updated_at, trace_id)
                values (?, ?, ?, ?, 1, 1, ?, ?)
                on conflict (lineage_id) do nothing
                """,
                fact.lineageId().value(),
                wire(fact.aggregateType()),
                fact.aggregateId(),
                fact.eventId(),
                Timestamp.from(command.createdAt()),
                fact.traceId());
        if (inserted != 1) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_HEAD_CONFLICT");
        }
        insertPropagation(command);
    }

    private void appendSuccessor(
            AccessInvalidationAppendCommand command,
            AccessInvalidationEventJsonCodec.ValidatedPayload validated) {
        AccessInvalidationFact fact = command.fact();
        List<LockedHead> heads = jdbc.query("""
                select current_event_id, current_version, fencing_token
                  from identity_access.ia_access_invalidation_lineage_head
                 where lineage_id=?
                   for update
                """,
                (rs, row) -> new LockedHead(
                        rs.getObject("current_event_id", UUID.class),
                        rs.getLong("current_version"),
                        rs.getLong("fencing_token")),
                fact.lineageId().value());
        LockedHead head = heads.stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "ACCESS_INVALIDATION_HEAD_MISSING"));
        if (head.version() != command.expectedHeadVersion()
                || head.fencingToken()
                        != command.expectedFencingToken()
                || fact.aggregateVersion() != head.version() + 1
                || !head.eventId().equals(fact.supersedesId())) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_HEAD_FENCED");
        }
        insertFactAndOutbox(command, validated);
        int updated = jdbc.update("""
                update identity_access.ia_access_invalidation_lineage_head
                   set current_event_id=?,
                       current_version=?,
                       fencing_token=fencing_token+1,
                       updated_at=?,
                       trace_id=?
                 where lineage_id=?
                   and current_event_id=?
                   and current_version=?
                   and fencing_token=?
                """,
                fact.eventId(),
                fact.aggregateVersion(),
                Timestamp.from(command.createdAt()),
                fact.traceId(),
                fact.lineageId().value(),
                head.eventId(),
                head.version(),
                head.fencingToken());
        if (updated != 1) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_HEAD_FENCED");
        }
        insertPropagation(command);
    }

    private void insertFactAndOutbox(
            AccessInvalidationAppendCommand command,
            AccessInvalidationEventJsonCodec.ValidatedPayload validated) {
        AccessInvalidationFact fact = command.fact();
        jdbc.update("""
                insert into identity_access.ia_access_invalidation_fact (
                  event_id, schema_version, event_type, producer, trace_id,
                  change_kind, reason_code, lineage_id, supersedes_id,
                  cause_event_id, aggregate_type, aggregate_id,
                  aggregate_version, invalidation_version, effective_at,
                  source_id, source_version, source_watermark,
                  dependency_vector, subject_token, scope_token,
                  object_digest, authorization_state, account_active,
                  r1_employment_valid, college_active, relation_effective,
                  policy_version, event_payload, payload_digest,
                  source_payload_digest, occurred_at, retain_until, legal_hold)
                values (
                  ?, 'ACCESS-INVALIDATION-DATA-1.0.0', ?,
                  'identity-access', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                  ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, ?, 'RFP-1.0.0',
                  cast(? as jsonb), ?, ?, ?, ?, ?)
                """,
                fact.eventId(),
                EVENT_TYPE,
                fact.traceId(),
                wire(fact.changeKind()),
                fact.reasonCode().name(),
                fact.lineageId().value(),
                fact.supersedesId(),
                fact.causeEventId(),
                wire(fact.aggregateType()),
                fact.aggregateId(),
                fact.aggregateVersion(),
                fact.invalidationVersion(),
                Timestamp.from(fact.effectiveAt()),
                fact.sourceVector().sourceId(),
                fact.sourceVector().sourceVersion(),
                fact.sourceVector().sourceWatermark(),
                dependencyVector(fact),
                fact.subjectSnapshot().subjectToken(),
                fact.subjectSnapshot().scopeToken(),
                fact.subjectSnapshot().objectDigest(),
                wire(fact.authorizationSnapshot().currentState()),
                fact.authorizationSnapshot().accountActive(),
                fact.authorizationSnapshot().r1EmploymentValid(),
                fact.authorizationSnapshot().collegeActive(),
                fact.authorizationSnapshot().relationEffective(),
                validated.canonicalPayload(),
                validated.payloadDigest(),
                fact.payloadDigest(),
                Timestamp.from(command.createdAt()),
                Timestamp.from(fact.retention().retainUntil()),
                fact.retention().legalHold());
        jdbc.update("""
                insert into identity_access.ia_access_invalidation_outbox (
                  outbox_id, event_id, event_type, event_payload,
                  payload_digest, delivery_key, status, attempts,
                  next_attempt_at, fencing_token, created_at, trace_id,
                  retain_until, legal_hold)
                values (
                  ?, ?, ?, cast(? as jsonb), ?,
                  ?, 'pending', 0, ?, 0, ?, ?, ?, ?)
                """,
                command.outboxId(),
                fact.eventId(),
                EVENT_TYPE,
                validated.canonicalPayload(),
                validated.payloadDigest(),
                "identity-access|" + fact.eventId(),
                Timestamp.from(command.createdAt()),
                Timestamp.from(command.createdAt()),
                fact.traceId(),
                Timestamp.from(fact.retention().retainUntil()),
                fact.retention().legalHold());
    }

    private void insertPropagation(
            AccessInvalidationAppendCommand command) {
        int required = jdbc.queryForObject("""
                select count(*)
                  from identity_access.ia_access_invalidation_consumer_registry
                 where lifecycle='active' and required
                """, Integer.class);
        AccessInvalidationFact fact = command.fact();
        jdbc.update("""
                insert into identity_access.ia_access_invalidation_propagation (
                  event_id, lineage_id, target_version,
                  required_consumer_count,
                  applied_required_consumer_count,
                  reconciliation_status, propagation_status,
                  last_checked_at, trace_id)
                values (?, ?, ?, ?, 0, 'pending', 'pending', ?, ?)
                """,
                fact.eventId(),
                fact.lineageId().value(),
                fact.aggregateVersion(),
                required,
                Timestamp.from(command.createdAt()),
                fact.traceId());
    }

    private String dependencyVector(AccessInvalidationFact fact) {
        List<Map<String, Object>> values =
                fact.sourceVector().dependencyVector().stream()
                        .map(value -> {
                            Map<String, Object> item =
                                    new LinkedHashMap<>();
                            item.put("feedId", value.feedId());
                            item.put("partitionId", value.partitionId());
                            item.put("watermark", value.watermark());
                            return item;
                        })
                        .toList();
        return json.writeValueAsString(values);
    }

    private List<AccessInvalidationFact> facts(
            String predicate, Object... arguments) {
        return jdbc.query("""
                select fact.event_id, fact.trace_id, fact.change_kind,
                       fact.reason_code, fact.lineage_id,
                       fact.supersedes_id, fact.cause_event_id,
                       fact.aggregate_type, fact.aggregate_id,
                       fact.aggregate_version, fact.invalidation_version,
                       fact.effective_at, fact.source_id,
                       fact.source_version, fact.source_watermark,
                       fact.dependency_vector::text as dependency_vector,
                       fact.subject_token, fact.scope_token,
                       fact.object_digest, fact.authorization_state,
                       fact.account_active, fact.r1_employment_valid,
                       fact.college_active, fact.relation_effective,
                       fact.retain_until, fact.legal_hold,
                       fact.source_payload_digest
                  from identity_access.ia_access_invalidation_fact fact
                """ + predicate,
                (rs, row) -> fact(rs),
                arguments);
    }

    private AccessInvalidationFact fact(ResultSet rs)
            throws SQLException {
        List<AccessInvalidationDependencyWatermark> dependencies =
                new java.util.ArrayList<>();
        json.readTree(rs.getString("dependency_vector"))
                .forEach(node -> dependencies.add(
                        new AccessInvalidationDependencyWatermark(
                                node.required("feedId").asText(),
                                node.required("partitionId").asText(),
                                node.required("watermark").asLong())));
        return new AccessInvalidationFact(
                rs.getObject("event_id", UUID.class),
                rs.getString("trace_id"),
                enumValue(
                        AccessInvalidationChangeKind.class,
                        rs.getString("change_kind")),
                AccessInvalidationReason.valueOf(
                        rs.getString("reason_code")),
                new AccessInvalidationLineageId(
                        rs.getString("lineage_id")),
                rs.getObject("supersedes_id", UUID.class),
                rs.getObject("cause_event_id", UUID.class),
                enumValue(
                        AccessInvalidationAggregateType.class,
                        rs.getString("aggregate_type")),
                rs.getString("aggregate_id"),
                rs.getLong("aggregate_version"),
                rs.getLong("invalidation_version"),
                rs.getTimestamp("effective_at").toInstant(),
                new AccessInvalidationSourceVector(
                        rs.getString("source_id"),
                        rs.getLong("source_version"),
                        rs.getLong("source_watermark"),
                        dependencies),
                new AccessInvalidationSubjectSnapshot(
                        rs.getString("subject_token"),
                        rs.getString("scope_token"),
                        rs.getString("object_digest"),
                        "ACCESS-INVALIDATION-TOKENIZATION-1.0.0"),
                new AccessInvalidationAuthorizationSnapshot(
                        enumValue(
                                AccessInvalidationAuthorizationState.class,
                                rs.getString("authorization_state")),
                        rs.getBoolean("account_active"),
                        rs.getBoolean("r1_employment_valid"),
                        rs.getBoolean("college_active"),
                        rs.getBoolean("relation_effective"),
                        "RFP-1.0.0"),
                new AccessInvalidationRetention(
                        "restricted",
                        "RS-1.0.0",
                        rs.getTimestamp("retain_until").toInstant(),
                        rs.getBoolean("legal_hold")),
                rs.getString("source_payload_digest"));
    }

    private static <T extends Enum<T>> T enumValue(
            Class<T> type, String wireValue) {
        return Enum.valueOf(
                type,
                wireValue.toUpperCase().replace('-', '_'));
    }

    private static String wire(Enum<?> value) {
        return value.name().toLowerCase().replace('_', '-');
    }

    private record LockedHead(
            UUID eventId, long version, long fencingToken) {}

    private record ExistingDigest(
            String eventPayloadDigest, String sourcePayloadDigest) {}
}
