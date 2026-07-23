package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.application.AuditUuidV7;
import cn.edu.suda.scholarsense.auditoperations.application.SearchAuditEvent;
import cn.edu.suda.scholarsense.auditoperations.application.SearchAuditPort;
import cn.edu.suda.scholarsense.auditoperations.application.AuditTokenQueryDomain;
import cn.edu.suda.scholarsense.auditoperations.application.AuditTokenQueryRequest;
import cn.edu.suda.scholarsense.auditoperations.application.AuditSearchTokenGateway;
import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditFact;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Commits the local read fact and producer-owned outbox atomically before results may escape. */
public final class JdbcSearchAuditRepository implements SearchAuditPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final AuditLedgerJsonCodec factCodec;
    private final AuditSearchTokenGateway tokenization;
    private final TrustedTimeSource time;

    public JdbcSearchAuditRepository(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            ObjectMapper json,
            AuditSearchTokenGateway tokenization,
            TrustedTimeSource time) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.transactions = java.util.Objects.requireNonNull(transactions);
        this.json = java.util.Objects.requireNonNull(json);
        this.factCodec = new AuditLedgerJsonCodec(json);
        this.tokenization = java.util.Objects.requireNonNull(tokenization);
        this.time = java.util.Objects.requireNonNull(time);
    }

    @Override
    public void commit(SearchAuditEvent event) {
        var trusted = time.now();
        Instant recordedAt = trusted.instant().isBefore(event.occurredAt())
                ? event.occurredAt() : trusted.instant();
        var tokens = tokenization.query(new AuditTokenQueryRequest(
                AuditTokenQueryDomain.ACTOR,
                event.requesterKey(),
                recordedAt.atZone(ZoneOffset.UTC).minusYears(3).toInstant()));
        if (tokens.isEmpty()) {
            throw new IllegalStateException("AUDIT_SEARCH_TOKEN_KEY_CATALOG_EMPTY");
        }
        var actor = tokens.getLast();
        UUID auditId = AuditUuidV7.generate(recordedAt);
        UUID eventId = AuditUuidV7.generate(recordedAt);
        Map<String, Object> authorization = new LinkedHashMap<>();
        authorization.put("decision", "accepted".equals(event.outcome()) ? "allow" : "deny");
        authorization.put("policyVersion", "RFP-1.0.0");
        authorization.put("scopeCodes", List.of("AUDIT_DOMAIN"));
        authorization.put("grantSearchTokens", List.of());
        authorization.put("notApplicableReason", null);
        LocalAuditFact fact = new LocalAuditFact(
                auditId,
                "LOCAL-AUDIT-FACT-1.0.0",
                "audit-operations",
                ActorType.USER,
                actor.value(),
                "accepted".equals(event.outcome())
                        ? List.of(event.action().contains("business") ? "R3" : "R7") : List.of(),
                authorization,
                "audit.search.execute",
                null,
                null,
                event.outcome(),
                event.errorCode() == null ? "AUDIT_SEARCH_COMPLETED" : event.errorCode(),
                "AUDIT_SEARCH",
                "AUDIT_DOMAIN",
                event.occurredAt(),
                recordedAt,
                trusted.profile(),
                null,
                actor.profileVersion(),
                actor.keyVersion(),
                event.traceId(),
                null,
                null,
                null,
                event.filterDigest(),
                Map.of("roleFieldPolicy", "RFP-1.0.0"),
                "RS-1.0.0");
        LocalAuditOutboxRecord outbox = LocalAuditOutboxRecord.forFact(eventId, fact, recordedAt);
        String factJson = factCodec.writeFact(fact);
        String envelopeJson = envelope(outbox, factJson);
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into audit_operations.ao_local_audit_fact (
                      audit_id, fact, filter_category_digest, created_at)
                    values (?, cast(? as jsonb), ?, ?)
                    """, auditId, factJson, event.filterDigest(), Timestamp.from(recordedAt));
            jdbc.update("""
                    insert into audit_operations.ao_local_audit_outbox (
                      event_id, audit_id, event_type, schema_version, envelope,
                      delivery_status, attempts, next_attempt_at, created_at)
                    values (?, ?, ?, ?, cast(? as jsonb), 'pending', 0, ?, ?)
                    """, eventId, auditId, outbox.eventType(), outbox.schemaVersion(), envelopeJson,
                    Timestamp.from(recordedAt), Timestamp.from(recordedAt));
        });
    }

    private String envelope(LocalAuditOutboxRecord outbox, String factJson) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("specversion", "1.0");
            envelope.put("id", outbox.eventId().toString());
            envelope.put("source", "urn:scholarsense:audit-operations");
            envelope.put("type", outbox.eventType());
            envelope.put("time", outbox.createdAt().toString());
            envelope.put("subject", "audit/" + outbox.auditId());
            envelope.put("datacontenttype", "application/json");
            envelope.put("data", json.readValue(factJson, Map.class));
            return json.writeValueAsString(envelope);
        } catch (JacksonException failure) {
            throw new IllegalStateException("AUDIT_SEARCH_AUDIT_SERIALIZATION_FAILED", failure);
        }
    }
}
