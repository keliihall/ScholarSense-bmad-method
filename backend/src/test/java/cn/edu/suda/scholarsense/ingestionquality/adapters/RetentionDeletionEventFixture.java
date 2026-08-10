package cn.edu.suda.scholarsense.ingestionquality.adapters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Builds full Task-0 owner-result events for legacy PostgreSQL retention probes. */
final class RetentionDeletionEventFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path EVENT_ROOT = Path.of("..").toAbsolutePath().normalize()
            .resolve("contracts/events/ingestion-quality");
    private static final Path COMPLETED = EVENT_ROOT.resolve(
            "fixtures/valid/quality-snapshot-deletion-completed-v1.json");
    private static final Path BLOCKED = EVENT_ROOT.resolve(
            "fixtures/valid/quality-snapshot-deletion-blocked-v1.json");

    private RetentionDeletionEventFixture() {}

    static byte[] payload(
            JdbcTemplate jdbc,
            UUID executionId,
            UUID eventId,
            UUID snapshotId,
            String immutableHash,
            String scopeDigest,
            UUID authorityEvidenceId,
            String result,
            String blockerCode,
            ScopeDefaults defaults) {
        try {
            List<byte[]> replayPayloads = jdbc.query("""
                    select payload_utf8
                      from ingestion_quality.iq_quality_snapshot_deletion_result
                     where result_event_id=? and execution_id=? and snapshot_id=?
                       and snapshot_immutable_hash=? and scope_digest=?
                       and authority_evidence_id=?
                    """, (rows, ignored) -> rows.getBytes(1), eventId, executionId,
                    snapshotId, immutableHash, scopeDigest, authorityEvidenceId);
            if (!replayPayloads.isEmpty()) {
                return replayPayloads.getFirst();
            }
            ObjectNode event = (ObjectNode) JSON.readTree(Files.readAllBytes(
                    result.equals("completed") ? COMPLETED : BLOCKED));
            ObjectNode data = (ObjectNode) event.required("data");
            ObjectNode scope = (ObjectNode) data.required("scope");

            List<Map<String, Object>> snapshots = jdbc.queryForList("""
                    select source_id, aggregate_version, evaluated_at, retention_due_at, trace_id
                      from ingestion_quality.iq_quality_snapshot
                     where snapshot_id=?
                    """, snapshotId);
            String sourceId = defaults.sourceId();
            long snapshotVersion = defaults.snapshotAggregateVersion();
            Instant evaluatedAt = defaults.evaluatedAt();
            Instant retentionDueAt = defaults.retentionDueAt();
            String traceId = defaults.traceId();
            if (!snapshots.isEmpty()) {
                Map<String, Object> snapshot = snapshots.getFirst();
                sourceId = snapshot.get("source_id").toString();
                snapshotVersion = ((Number) snapshot.get("aggregate_version")).longValue();
                evaluatedAt = ((Timestamp) snapshot.get("evaluated_at")).toInstant();
                retentionDueAt = ((Timestamp) snapshot.get("retention_due_at")).toInstant();
                traceId = snapshot.get("trace_id").toString().trim();
            }

            List<Map<String, Object>> authorities = jdbc.queryForList("""
                    select registry_version, registry_digest, members_digest,
                           legal_hold_clear, legal_hold_checked_at,
                           consumer_attestations_payload_utf8, watermarks_checked_at,
                           trusted_observed_at
                      from ingestion_quality
                        .iq_quality_snapshot_retention_authority_evidence
                     where authority_evidence_id=?
                    """, authorityEvidenceId);
            Instant occurredAt = authorities.isEmpty()
                    ? jdbc.queryForObject(
                            "select date_trunc('milliseconds', statement_timestamp())",
                            Timestamp.class).toInstant()
                    : ((Timestamp) authorities.getFirst().get("trusted_observed_at")).toInstant();

            long aggregateVersion = jdbc.queryForObject("""
                    select coalesce(max(aggregate_version),0)+1
                      from ingestion_quality.iq_quality_snapshot_deletion_result
                     where execution_id=?
                    """, Long.class, executionId);
            UUID predecessor = jdbc.query("""
                    select result_event_id
                      from ingestion_quality.iq_quality_snapshot_deletion_result
                     where execution_id=?
                     order by aggregate_version desc limit 1
                    """, (rows, ignored) -> (UUID) rows.getObject(1), executionId)
                    .stream().findFirst().orElse(null);

            String occurred = occurredAt.toString();
            event.put("id", eventId.toString());
            event.put("subject", "quality-snapshot/" + snapshotId);
            event.put("time", occurred);
            event.put("traceparent", "00-" + traceId + "-bbbbbbbbbbbbbbbb-01");
            data.put("correlationId", executionId.toString());
            data.put("causationId", authorityEvidenceId.toString());
            data.put("eventId", eventId.toString());
            data.put("aggregateId", executionId.toString());
            data.put("aggregateVersion", aggregateVersion);
            data.put("executionId", executionId.toString());
            if (predecessor == null) data.putNull("supersedesResultId");
            else data.put("supersedesResultId", predecessor.toString());
            data.put("occurredAt", occurred);
            data.put("traceId", traceId);
            data.put("result", result);

            scope.put("snapshotId", snapshotId.toString());
            scope.put("sourceId", sourceId);
            scope.put("snapshotAggregateVersion", snapshotVersion);
            scope.put("evaluatedAt", evaluatedAt.toString());
            scope.put("retentionDueAt", retentionDueAt.toString());
            scope.put("snapshotImmutableHash", immutableHash);
            scope.put("scopeDigest", scopeDigest);

            configureGuards(data, authorities, snapshotId, immutableHash, scopeDigest,
                    snapshotVersion, occurredAt);
            ArrayNode blockers = (ArrayNode) data.required("blockerCodes");
            blockers.removeAll();
            if (blockerCode != null) blockers.add(blockerCode);
            ((ArrayNode) data.required("failureCodes")).removeAll();

            if (result.equals("completed")) {
                data.put("deletionCommittedAt", occurred);
                ((ObjectNode) data.required("backup")).put(
                        "backupExpiryDueAt", occurredAt.plus(11, ChronoUnit.DAYS).toString());
                long metricCount = jdbc.queryForObject("""
                        select count(*)
                          from ingestion_quality.iq_quality_snapshot_metric
                         where snapshot_id=?
                        """, Long.class, snapshotId);
                UUID transactionId = transactionId(eventId, executionId);
                configureCompletedTargets(data, snapshotId, metricCount, transactionId);
                refreshTransactionDigest(event);
            } else {
                data.putNull("deletionCommittedAt");
                ((ObjectNode) data.required("backup")).putNull("backupExpiryDueAt");
                configureBlockedTargets(data);
            }
            return canonicalBytes(event);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot build retention event fixture", failure);
        }
    }

    static byte[] mutateCausation(byte[] payload, UUID causationId) {
        try {
            ObjectNode event = (ObjectNode) JSON.readTree(payload);
            ((ObjectNode) event.required("data")).put("causationId", causationId.toString());
            return canonicalBytes(event);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException(failure);
        }
    }

    private static void configureGuards(
            ObjectNode data,
            List<Map<String, Object>> authorities,
            UUID snapshotId,
            String immutableHash,
            String scopeDigest,
            long snapshotVersion,
            Instant occurredAt) throws JacksonException {
        ObjectNode guards = (ObjectNode) data.required("guards");
        ObjectNode trusted = (ObjectNode) guards.required("trustedTime");
        ObjectNode hold = (ObjectNode) guards.required("legalHold");
        ObjectNode registry = (ObjectNode) guards.required("consumerRegistry");
        ArrayNode watermarks = (ArrayNode) guards.required("consumerWatermarks");
        if (authorities.isEmpty()) {
            trusted.put("status", "unavailable");
            trusted.putNull("observedAt");
            hold.put("status", "unavailable");
            hold.put("checkedScopeDigest", scopeDigest);
            hold.put("matchedCount", 0);
            ((ArrayNode) hold.required("matchedScopeDigests")).removeAll();
            hold.putNull("checkedAt");
            registry.put("status", "unavailable");
            registry.put("registryVersion", "QUALITY-SNAPSHOT-CONSUMER-REGISTRY-1.0.0");
            registry.put("registryDigest", digest("registry-unavailable"));
            ((ArrayNode) registry.required("members")).removeAll();
            registry.putNull("conformanceAnchorId");
            registry.putNull("checkedAt");
            registry.putNull("authorityEvidence");
            guards.putNull("consumerWatermarksCheckedAt");
            watermarks.removeAll();
            return;
        }

        Map<String, Object> authority = authorities.getFirst();
        Instant checkedAt = ((Timestamp) authority.get("legal_hold_checked_at")).toInstant();
        Instant watermarksAt = ((Timestamp) authority.get("watermarks_checked_at")).toInstant();
        boolean clear = (Boolean) authority.get("legal_hold_clear");
        JsonNode attestationPayload = JSON.readTree(
                (byte[]) authority.get("consumer_attestations_payload_utf8"));
        ArrayNode consumers = (ArrayNode) attestationPayload.required("consumers");

        trusted.put("status", "available");
        trusted.put("observedAt", occurredAt.toString());
        hold.put("status", clear ? "clear" : "matched");
        hold.put("checkedScopeDigest", scopeDigest);
        hold.put("matchedCount", clear ? 0 : 1);
        ArrayNode matched = (ArrayNode) hold.required("matchedScopeDigests");
        matched.removeAll();
        if (!clear) matched.add(scopeDigest);
        hold.put("checkedAt", checkedAt.toString());

        ArrayNode members = (ArrayNode) registry.required("members");
        members.removeAll();
        consumers.forEach(consumer -> {
            ObjectNode member = JSON.createObjectNode();
            member.set("consumerId", consumer.required("consumerId").deepCopy());
            member.set("registryMembership",
                    consumer.required("registryMembership").deepCopy());
            member.set("lifecycleStatus", consumer.required("lifecycleStatus").deepCopy());
            members.add(member);
        });
        String anchor = consumers.isEmpty()
                ? "empty-consumer-set"
                : "active-clue-care-consumer-set";
        registry.put("status", "available");
        registry.put("registryVersion", authority.get("registry_version").toString().trim());
        registry.put("registryDigest", authority.get("registry_digest").toString().trim());
        registry.put("conformanceAnchorId", anchor);
        registry.put("checkedAt", checkedAt.toString());
        ObjectNode evidence = registry.putObject("authorityEvidence");
        evidence.put("provider", "quality-snapshot-consumer-registry-conformance-anchor");
        evidence.put("evidenceRef",
                "contract://quality-snapshot-retention/consumer-registry/" + anchor);
        evidence.put("scopeDigest", scopeDigest);
        evidence.put("registryVersion", authority.get("registry_version").toString().trim());
        evidence.put("registryDigest", authority.get("registry_digest").toString().trim());
        evidence.put("membersDigest", authority.get("members_digest").toString().trim());
        evidence.put("checkedAt", checkedAt.toString());
        evidence.put("verificationStatus", "contract-conformance");
        evidence.put("runtimeEvidenceClaim", "none");
        guards.put("consumerWatermarksCheckedAt", watermarksAt.toString());

        for (JsonNode consumer : consumers) {
            ObjectNode attestation = (ObjectNode) consumer.required("attestation");
            attestation.put("snapshotId", snapshotId.toString());
            attestation.put("snapshotImmutableHash", immutableHash);
            attestation.put("requiredAggregateVersion", snapshotVersion);
            attestation.put("scopeDigest", scopeDigest);
        }
        watermarks.removeAll();
        consumers.forEach(item -> watermarks.add(item.deepCopy()));
    }

    private static void configureCompletedTargets(
            ObjectNode data, UUID snapshotId, long metricCount, UUID transactionId) {
        completedTarget(target(data, "onlineSnapshot"), 1,
                digest("snapshot-delete:" + snapshotId), transactionId);
        completedTarget(target(data, "onlineMetrics"), metricCount,
                digest("metric-delete:" + snapshotId + ":" + metricCount), transactionId);
        for (String name : List.of("readModels", "indexes", "caches", "objects")) {
            ObjectNode target = target(data, name);
            target.put("status", "not-applicable");
            target.put("selectedCount", 0);
            target.put("deletedCount", 0);
            target.put("remainingCount", 0);
            target.putNull("evidenceDigest");
            target.putNull("errorCode");
            target.putNull("transactionId");
            target.putNull("transactionEvidenceDigest");
        }
    }

    private static void completedTarget(
            ObjectNode target, long count, String evidenceDigest, UUID transactionId) {
        target.put("status", "deleted");
        target.put("selectedCount", count);
        target.put("deletedCount", count);
        target.put("remainingCount", 0);
        target.put("evidenceDigest", evidenceDigest);
        target.putNull("errorCode");
        target.put("transactionId", transactionId.toString());
        target.putNull("transactionEvidenceDigest");
    }

    private static void configureBlockedTargets(ObjectNode data) {
        for (String name : List.of(
                "onlineSnapshot", "onlineMetrics", "readModels", "indexes", "caches", "objects")) {
            ObjectNode target = target(data, name);
            target.put("status", "not-attempted");
            target.put("selectedCount", 0);
            target.put("deletedCount", 0);
            target.put("remainingCount", 0);
            target.putNull("evidenceDigest");
            target.putNull("errorCode");
            target.putNull("transactionId");
            target.putNull("transactionEvidenceDigest");
        }
    }

    private static ObjectNode target(ObjectNode data, String name) {
        return (ObjectNode) data.required("ownerLocalResults").required(name);
    }

    private static void refreshTransactionDigest(ObjectNode event) {
        ObjectNode data = (ObjectNode) event.required("data");
        ObjectNode material = JSON.createObjectNode();
        material.set("executionId", data.required("executionId").deepCopy());
        material.set("scopeDigest", data.required("scope").required("scopeDigest").deepCopy());
        material.set("result", data.required("result").deepCopy());
        material.set("deletionCommittedAt", data.required("deletionCommittedAt").deepCopy());
        material.set("transactionId", data.required("ownerLocalResults")
                .required("onlineSnapshot").required("transactionId").deepCopy());
        material.set("onlineSnapshot", transactionTarget(
                data.required("ownerLocalResults").required("onlineSnapshot")));
        material.set("onlineMetrics", transactionTarget(
                data.required("ownerLocalResults").required("onlineMetrics")));
        String digest = "sha256:" + sha256(canonicalBytes(material));
        target(data, "onlineSnapshot").put("transactionEvidenceDigest", digest);
        target(data, "onlineMetrics").put("transactionEvidenceDigest", digest);
    }

    private static ObjectNode transactionTarget(JsonNode target) {
        ObjectNode material = JSON.createObjectNode();
        for (String field : List.of(
                "status", "selectedCount", "deletedCount", "remainingCount",
                "evidenceDigest", "errorCode")) {
            material.set(field, target.required(field).deepCopy());
        }
        return material;
    }

    private static UUID transactionId(UUID eventId, UUID executionId) {
        UUID candidate = new UUID(
                eventId.getMostSignificantBits(), eventId.getLeastSignificantBits() ^ 0x1000L);
        if (candidate.equals(executionId) || candidate.equals(eventId)) {
            candidate = new UUID(
                    eventId.getMostSignificantBits(), eventId.getLeastSignificantBits() ^ 0x2000L);
        }
        return candidate;
    }

    private static String digest(String value) {
        return "sha256:" + sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] canonicalBytes(JsonNode value) {
        try {
            return JSON.writeValueAsBytes(canonicalValue(value));
        } catch (JacksonException failure) {
            throw new IllegalArgumentException(failure);
        }
    }

    private static Object canonicalValue(JsonNode value) {
        if (value.isNull()) return null;
        if (value.isTextual()) return value.asText();
        if (value.isBoolean()) return value.asBoolean();
        if (value.isIntegralNumber()) return value.bigIntegerValue();
        if (value.isArray()) {
            List<Object> result = new ArrayList<>();
            value.forEach(item -> result.add(canonicalValue(item)));
            return result;
        }
        if (value.isObject()) {
            Map<String, Object> result = new TreeMap<>();
            value.forEachEntry((name, item) -> result.put(name, canonicalValue(item)));
            return result;
        }
        throw new IllegalArgumentException("integer JSON only");
    }

    record ScopeDefaults(
            String sourceId,
            long snapshotAggregateVersion,
            Instant evaluatedAt,
            Instant retentionDueAt,
            String traceId) {}
}
