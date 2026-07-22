package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditFact;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Explicit v1 fact codec; storage does not depend on record reflection or enum serialization defaults. */
final class AuditLedgerJsonCodec {
    private static final Set<String> FACT_FIELDS = Set.of(
            "auditId", "schemaVersion", "producerModule", "actorType", "actorSearchToken",
            "roleIds", "authorizationContext", "action", "objectType", "objectSearchToken",
            "outcome", "reasonCode", "purpose", "projectionScope", "occurredAt", "recordedAt",
            "timeSourceProfile", "sourceIpSearchToken", "tokenizationProfileVersion", "keyVersion",
            "traceId", "aggregateType", "aggregateIdSearchToken", "aggregateVersion",
            "idempotencyKeyDigest", "policyVersions", "retentionScheduleVersion");
    private static final Set<String> TIME_FIELDS = Set.of(
            "sourceId", "profileVersion", "offsetMs", "observedAt", "freshUntil", "evidenceRef");

    private final ObjectMapper json;

    AuditLedgerJsonCodec(ObjectMapper json) {
        this.json = json;
    }

    String writeFact(LocalAuditFact fact) {
        try {
            return json.writeValueAsString(factMap(fact));
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID", failure);
        }
    }

    LocalAuditFact readFact(String payload) {
        try {
            JsonNode root = json.readTree(payload);
            requireExactObject(root, FACT_FIELDS);
            JsonNode time = requiredObject(root, "timeSourceProfile");
            requireExactObject(time, TIME_FIELDS);
            return new LocalAuditFact(
                    UUID.fromString(text(root, "auditId")),
                    text(root, "schemaVersion"),
                    text(root, "producerModule"),
                    actorType(text(root, "actorType")),
                    nullableText(root, "actorSearchToken"),
                    stringList(root, "roleIds"),
                    object(requiredObject(root, "authorizationContext")),
                    text(root, "action"),
                    nullableText(root, "objectType"),
                    nullableText(root, "objectSearchToken"),
                    text(root, "outcome"),
                    text(root, "reasonCode"),
                    nullableText(root, "purpose"),
                    nullableText(root, "projectionScope"),
                    Instant.parse(text(root, "occurredAt")),
                    Instant.parse(text(root, "recordedAt")),
                    new TimeSourceProfile(
                            text(time, "sourceId"),
                            text(time, "profileVersion"),
                            requiredInt(time, "offsetMs"),
                            Instant.parse(text(time, "observedAt")),
                            Instant.parse(text(time, "freshUntil")),
                            text(time, "evidenceRef")),
                    nullableText(root, "sourceIpSearchToken"),
                    text(root, "tokenizationProfileVersion"),
                    text(root, "keyVersion"),
                    text(root, "traceId"),
                    nullableText(root, "aggregateType"),
                    nullableText(root, "aggregateIdSearchToken"),
                    nullableLong(root, "aggregateVersion"),
                    nullableText(root, "idempotencyKeyDigest"),
                    stringMap(root, "policyVersions"),
                    text(root, "retentionScheduleVersion"));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID", failure);
        }
    }

    private static Map<String, Object> factMap(LocalAuditFact fact) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("auditId", fact.auditId().toString());
        value.put("schemaVersion", fact.schemaVersion());
        value.put("producerModule", fact.producerModule());
        value.put("actorType", fact.actorType().wireName());
        value.put("actorSearchToken", fact.actorSearchToken());
        value.put("roleIds", fact.roleIds());
        value.put("authorizationContext", fact.authorizationContext());
        value.put("action", fact.action());
        value.put("objectType", fact.objectType());
        value.put("objectSearchToken", fact.objectSearchToken());
        value.put("outcome", fact.outcome());
        value.put("reasonCode", fact.reasonCode());
        value.put("purpose", fact.purpose());
        value.put("projectionScope", fact.projectionScope());
        value.put("occurredAt", fact.occurredAt().toString());
        value.put("recordedAt", fact.recordedAt().toString());
        value.put("timeSourceProfile", timeMap(fact.timeSourceProfile()));
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

    private static Map<String, Object> timeMap(TimeSourceProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceId", profile.sourceId());
        value.put("profileVersion", profile.profileVersion());
        value.put("offsetMs", profile.offsetMs());
        value.put("observedAt", profile.observedAt().toString());
        value.put("freshUntil", profile.freshUntil().toString());
        value.put("evidenceRef", profile.evidenceRef());
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID");
        }
        return value.asText();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID");
        }
        return value.asText();
    }

    private static Long nullableLong(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID");
        }
        try {
            return Long.valueOf(value.asText());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID", invalid);
        }
    }

    private static List<String> stringList(JsonNode parent, String field) {
        JsonNode node = parent.required(field);
        if (!node.isArray()) {
            throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID");
        }
        List<String> result = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID");
            }
            result.add(value.asText());
        });
        return List.copyOf(result);
    }

    private static Map<String, String> stringMap(JsonNode parent, String field) {
        JsonNode node = requiredObject(parent, field);
        Map<String, String> result = new LinkedHashMap<>();
        node.forEachEntry((key, value) -> {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID");
            }
            result.put(key, value.asText());
        });
        return result;
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.required(field);
        if (!value.isObject()) {
            throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID");
        }
        return value;
    }

    private static int requiredInt(JsonNode parent, String field) {
        JsonNode value = parent.required(field);
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID");
        }
        try {
            return Integer.parseInt(value.asText());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID", invalid);
        }
    }

    private static void requireExactObject(JsonNode node, Set<String> expectedFields) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID");
        }
        Set<String> actualFields = new HashSet<>();
        node.forEachEntry((key, ignored) -> actualFields.add(key));
        if (!actualFields.equals(expectedFields)) {
            throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(JsonNode node) {
        Object value = javaValue(node);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID");
        }
        return (Map<String, Object>) map;
    }

    private static Object javaValue(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(item -> values.add(javaValue(item)));
            return List.copyOf(values);
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            node.forEachEntry((key, item) -> values.put(key, javaValue(item)));
            return values;
        }
        throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID");
    }

    private static ActorType actorType(String value) {
        return switch (value) {
            case "user" -> ActorType.USER;
            case "anonymous" -> ActorType.ANONYMOUS;
            case "service" -> ActorType.SERVICE;
            default -> throw new IllegalArgumentException("AUDIT_LEDGER_PAYLOAD_INVALID");
        };
    }
}
