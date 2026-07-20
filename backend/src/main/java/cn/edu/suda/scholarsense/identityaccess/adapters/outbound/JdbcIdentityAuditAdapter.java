package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditPort;
import cn.edu.suda.scholarsense.identityaccess.application.IdentityAuditRecord;
import cn.edu.suda.scholarsense.identityaccess.domain.IdentityAccessException;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditFact;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Writes exactly one identity-owned fact and one identity-owned audit outbox row atomically. */
public final class JdbcIdentityAuditAdapter implements IdentityAuditPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcIdentityAuditAdapter(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public void append(IdentityAuditRecord record) {
        try {
            transactions.executeWithoutResult(status -> appendInTransaction(record));
        } catch (IdentityAccessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IdentityAccessException(
                    "IDENTITY_AUDIT_UNAVAILABLE", "security audit is unavailable");
        }
    }

    private void appendInTransaction(IdentityAuditRecord record) {
        LocalAuditFact fact = record.fact();
        String actorLegacy = fact.actorSearchToken() == null ? "anonymous" : fact.actorSearchToken();
        String objectLegacy = fact.objectSearchToken() == null ? "not-applicable" : fact.objectSearchToken();
        String sourceIpLegacy = fact.sourceIpSearchToken() == null ? "not-applicable" : fact.sourceIpSearchToken();
        jdbc.update("""
                insert into identity_access.ia_local_audit_fact (
                  audit_id, actor_pseudonym, session_pseudonym, action, result, occurred_at,
                  source_ip_pseudonym, trace_id, profile_version,
                  producer_module, actor_type, actor_search_token, role_ids,
                  authorization_context, object_type, object_search_token, outcome, reason_code,
                  purpose, projection_scope, recorded_at, time_source_profile, source_ip_search_token,
                  tokenization_profile_version, key_version, aggregate_type, aggregate_id_search_token,
                  aggregate_version, idempotency_key_digest, policy_versions, retention_schedule_version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb),
                        ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
                """,
                fact.auditId(), actorLegacy, objectLegacy, fact.action(), fact.outcome(),
                timestamp(fact.occurredAt()), sourceIpLegacy, fact.traceId(),
                fact.policyVersions().getOrDefault("identitySessionPolicy", "ISP-1.0.0"),
                fact.producerModule(), fact.actorType().wireName(),
                fact.actorSearchToken(), toJson(fact.roleIds()), toJson(authorization(fact)),
                fact.objectType(), fact.objectSearchToken(), fact.outcome(), fact.reasonCode(),
                fact.purpose(), fact.projectionScope(), timestamp(fact.recordedAt()),
                toJson(timeSource(fact)), fact.sourceIpSearchToken(),
                fact.tokenizationProfileVersion(), fact.keyVersion(), fact.aggregateType(),
                fact.aggregateIdSearchToken(), fact.aggregateVersion(), fact.idempotencyKeyDigest(),
                toJson(fact.policyVersions()), fact.retentionScheduleVersion());

        var outbox = record.outbox();
        jdbc.update("""
                insert into identity_access.ia_local_audit_outbox (
                  event_id, audit_id, event_type, schema_version, envelope, created_at,
                  delivery_status, attempts, next_attempt_at)
                values (?, ?, ?, ?, cast(? as jsonb), ?, 'pending', 0, ?)
                """,
                outbox.eventId(), outbox.auditId(), outbox.eventType(), outbox.schemaVersion(),
                toJson(envelope(record)), timestamp(outbox.createdAt()), timestamp(outbox.createdAt()));
    }

    private Map<String, Object> envelope(IdentityAuditRecord record) {
        var outbox = record.outbox();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("specversion", "1.0");
        value.put("id", outbox.eventId().toString());
        value.put("source", "urn:scholarsense:identity-access");
        value.put("type", outbox.eventType());
        value.put("time", outbox.createdAt().toString());
        value.put("subject", "audit/" + outbox.auditId());
        value.put("datacontenttype", "application/json");
        value.put("data", fact(record.fact()));
        return value;
    }

    private static Map<String, Object> fact(LocalAuditFact fact) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("auditId", fact.auditId().toString());
        value.put("schemaVersion", fact.schemaVersion());
        value.put("producerModule", fact.producerModule());
        value.put("actorType", fact.actorType().wireName());
        value.put("actorSearchToken", fact.actorSearchToken());
        value.put("roleIds", fact.roleIds());
        value.put("authorizationContext", authorization(fact));
        value.put("action", fact.action());
        value.put("objectType", fact.objectType());
        value.put("objectSearchToken", fact.objectSearchToken());
        value.put("outcome", fact.outcome());
        value.put("reasonCode", fact.reasonCode());
        value.put("purpose", fact.purpose());
        value.put("projectionScope", fact.projectionScope());
        value.put("occurredAt", fact.occurredAt().toString());
        value.put("recordedAt", fact.recordedAt().toString());
        value.put("timeSourceProfile", timeSource(fact));
        value.put("sourceIpSearchToken", fact.sourceIpSearchToken());
        value.put("tokenizationProfileVersion", fact.tokenizationProfileVersion());
        value.put("keyVersion", fact.keyVersion());
        value.put("traceId", fact.traceId());
        value.put("aggregateType", fact.aggregateType());
        value.put("aggregateIdSearchToken", fact.aggregateIdSearchToken());
        value.put("aggregateVersion", fact.aggregateVersion());
        value.put("idempotencyKeyDigest", fact.idempotencyKeyDigest());
        value.put("policyVersions", fact.policyVersions());
        value.put("retentionScheduleVersion", fact.retentionScheduleVersion());
        return value;
    }

    private static Map<String, Object> authorization(LocalAuditFact fact) {
        return fact.authorizationContext();
    }

    private static Map<String, Object> timeSource(LocalAuditFact fact) {
        var profile = fact.timeSourceProfile();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceId", profile.sourceId());
        value.put("profileVersion", profile.profileVersion());
        value.put("offsetMs", profile.offsetMs());
        value.put("observedAt", profile.observedAt().toString());
        value.put("freshUntil", profile.freshUntil().toString());
        value.put("evidenceRef", profile.evidenceRef());
        return value;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IdentityAccessException(
                    "IDENTITY_AUDIT_CONTRACT_INVALID", "security audit is unavailable");
        }
    }

    private static Timestamp timestamp(java.time.Instant value) {
        return Timestamp.from(value);
    }
}
