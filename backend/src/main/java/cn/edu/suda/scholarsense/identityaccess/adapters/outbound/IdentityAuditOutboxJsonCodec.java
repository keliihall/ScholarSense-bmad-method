package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.shared.outbox.ActorType;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditFact;
import cn.edu.suda.scholarsense.shared.outbox.LocalAuditOutboxRecord;
import cn.edu.suda.scholarsense.shared.time.TimeSourceProfile;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strict reader for the identity-owned LOCAL-AUDIT-OUTBOX-1.0.0 envelope. */
final class IdentityAuditOutboxJsonCodec {
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "specversion", "id", "source", "type", "time", "subject",
            "datacontenttype", "data");
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

    IdentityAuditOutboxJsonCodec(ObjectMapper json) {
        this.json = json;
    }

    LocalAuditOutboxRecord read(
            UUID eventId,
            UUID auditId,
            String eventType,
            String schemaVersion,
            Instant createdAt,
            String envelopeJson) {
        try {
            JsonNode envelope = json.readTree(envelopeJson);
            requireExactObject(envelope, ENVELOPE_FIELDS);
            JsonNode fact = requiredObject(envelope, "data");
            requireExactObject(fact, FACT_FIELDS);
            Instant envelopeTime = Instant.parse(text(envelope, "time"));
            if (!"1.0".equals(text(envelope, "specversion"))
                    || !eventId.toString().equals(text(envelope, "id"))
                    || !eventType.equals(text(envelope, "type"))
                    || !createdAt.truncatedTo(ChronoUnit.MICROS)
                            .equals(envelopeTime.truncatedTo(ChronoUnit.MICROS))
                    || !("audit/" + auditId).equals(text(envelope, "subject"))
                    || !"application/json".equals(text(envelope, "datacontenttype"))) {
                throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_ENVELOPE_MISMATCH");
            }
            JsonNode time = requiredObject(fact, "timeSourceProfile");
            requireExactObject(time, TIME_FIELDS);
            LocalAuditFact payload = new LocalAuditFact(
                    UUID.fromString(text(fact, "auditId")),
                    text(fact, "schemaVersion"),
                    text(fact, "producerModule"),
                    actorType(text(fact, "actorType")),
                    nullableText(fact, "actorSearchToken"),
                    strings(fact, "roleIds"),
                    object(requiredObject(fact, "authorizationContext")),
                    text(fact, "action"),
                    nullableText(fact, "objectType"),
                    nullableText(fact, "objectSearchToken"),
                    text(fact, "outcome"),
                    text(fact, "reasonCode"),
                    nullableText(fact, "purpose"),
                    nullableText(fact, "projectionScope"),
                    Instant.parse(text(fact, "occurredAt")),
                    Instant.parse(text(fact, "recordedAt")),
                    new TimeSourceProfile(
                            text(time, "sourceId"), text(time, "profileVersion"),
                            requiredIntInRange(time, "offsetMs", -100, 100),
                            Instant.parse(text(time, "observedAt")),
                            Instant.parse(text(time, "freshUntil")),
                            text(time, "evidenceRef")),
                    nullableText(fact, "sourceIpSearchToken"),
                    text(fact, "tokenizationProfileVersion"),
                    text(fact, "keyVersion"),
                    text(fact, "traceId"),
                    nullableText(fact, "aggregateType"),
                    nullableText(fact, "aggregateIdSearchToken"),
                    nullableLong(fact, "aggregateVersion"),
                    nullableText(fact, "idempotencyKeyDigest"),
                    stringMap(fact, "policyVersions"),
                    text(fact, "retentionScheduleVersion"));
            if (!("urn:scholarsense:" + payload.producerModule())
                    .equals(text(envelope, "source"))) {
                throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_ENVELOPE_MISMATCH");
            }
            return new LocalAuditOutboxRecord(
                    eventId, auditId, eventType, schemaVersion,
                    payload.producerModule(), createdAt, payload);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_CONTRACT_INVALID", failure);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_CONTRACT_INVALID");
        }
        return value.asText();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_CONTRACT_INVALID");
        }
        return value.asText();
    }

    private static Long nullableLong(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_CONTRACT_INVALID");
        }
        try {
            return Long.valueOf(value.asText());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_CONTRACT_INVALID", invalid);
        }
    }

    private static List<String> strings(JsonNode parent, String field) {
        JsonNode node = parent.required(field);
        if (!node.isArray()) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_CONTRACT_INVALID");
        }
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_CONTRACT_INVALID");
            }
            values.add(value.asText());
        });
        return List.copyOf(values);
    }

    private static Map<String, String> stringMap(JsonNode parent, String field) {
        JsonNode node = requiredObject(parent, field);
        Map<String, String> values = new LinkedHashMap<>();
        node.forEachEntry((key, value) -> {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_CONTRACT_INVALID");
            }
            values.put(key, value.asText());
        });
        return values;
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.required(field);
        if (!value.isObject()) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_CONTRACT_INVALID");
        }
        return value;
    }

    private static int requiredInt(JsonNode parent, String field) {
        JsonNode value = parent.required(field);
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_CONTRACT_INVALID");
        }
        try {
            return Integer.parseInt(value.asText());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_CONTRACT_INVALID", invalid);
        }
    }

    private static int requiredIntInRange(
            JsonNode parent, String field, int minimum, int maximum) {
        int value = requiredInt(parent, field);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_CONTRACT_INVALID");
        }
        return value;
    }

    private static void requireExactObject(JsonNode node, Set<String> expectedFields) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_CONTRACT_INVALID");
        }
        Set<String> actualFields = new HashSet<>();
        node.forEachEntry((key, ignored) -> actualFields.add(key));
        if (!actualFields.equals(expectedFields)) {
            throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_CONTRACT_INVALID");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(JsonNode node) {
        return (Map<String, Object>) javaValue(node);
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
            node.forEach(value -> values.add(javaValue(value)));
            return List.copyOf(values);
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            node.forEachEntry((key, value) -> values.put(key, javaValue(value)));
            return values;
        }
        throw new IllegalArgumentException("IDENTITY_AUDIT_OUTBOX_CONTRACT_INVALID");
    }

    private static ActorType actorType(String value) {
        return switch (value) {
            case "user" -> ActorType.USER;
            case "anonymous" -> ActorType.ANONYMOUS;
            case "service" -> ActorType.SERVICE;
            default -> throw new IllegalArgumentException("IDENTITY_AUDIT_ACTOR_TYPE_INVALID");
        };
    }
}
