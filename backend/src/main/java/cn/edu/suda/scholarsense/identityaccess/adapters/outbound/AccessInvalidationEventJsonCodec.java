package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationEventCodecPort;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationFact;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Writes the locked CloudEvents 1.0 invalidation wire shape. */
public final class AccessInvalidationEventJsonCodec
        implements AccessInvalidationEventCodecPort {
    private static final String EVENT_TYPE =
            "scholarsense.identity-access.responsibility.changed.v1";
    private final ObjectMapper json;

    public AccessInvalidationEventJsonCodec(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String encode(AccessInvalidationFact fact) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", "ACCESS-INVALIDATION-DATA-1.0.0");
        data.put("eventId", fact.eventId().toString());
        data.put("traceId", fact.traceId());
        data.put("producer", "identity-access");
        data.put("changeKind", wire(fact.changeKind()));
        data.put("reasonCode", fact.reasonCode().name());
        data.put("lineageId", fact.lineageId().value());
        data.put(
                "supersedesId",
                fact.supersedesId() == null
                        ? null
                        : fact.supersedesId().toString());
        data.put(
                "causeEventId",
                fact.causeEventId() == null
                        ? null
                        : fact.causeEventId().toString());
        data.put("aggregateType", wire(fact.aggregateType()));
        data.put("aggregateId", fact.aggregateId());
        data.put("aggregateVersion", fact.aggregateVersion());
        data.put("invalidationVersion", fact.invalidationVersion());
        data.put("effectiveAt", fact.effectiveAt().toString());
        data.put("sourceVector", sourceVector(fact));
        data.put("subjectSnapshot", subjectSnapshot(fact));
        data.put(
                "authorizationSnapshot",
                authorizationSnapshot(fact));
        data.put("retention", retention(fact));

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", fact.eventId().toString());
        envelope.put("source", "urn:scholarsense:identity-access");
        envelope.put("type", EVENT_TYPE);
        envelope.put(
                "subject",
                "responsibility/" + fact.aggregateId());
        envelope.put("time", fact.effectiveAt().toString());
        envelope.put("datacontenttype", "application/json");
        envelope.put("data", data);
        String encoded = json.writeValueAsString(envelope);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_PAYLOAD_TOO_LARGE");
        }
        return encoded;
    }

    /**
     * Validates the complete wire event against the immutable fact and returns
     * the one canonical representation used for persistence and hashing.
     * Object member order and insignificant whitespace in the received JSON do
     * not affect the digest; missing, additional, or semantically different
     * fields are rejected.
     */
    public ValidatedPayload validate(
            AccessInvalidationFact fact, String eventPayload) {
        if (eventPayload == null
                || eventPayload.getBytes(StandardCharsets.UTF_8).length
                        > 64 * 1024) {
            throw invalidPayload(null);
        }
        try {
            String canonicalPayload = encode(fact);
            if (!json.readTree(canonicalPayload)
                    .equals(json.readTree(eventPayload))) {
                throw invalidPayload(null);
            }
            return new ValidatedPayload(
                    canonicalPayload, digest(canonicalPayload));
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw invalidPayload(invalid);
        }
    }

    private static String digest(String canonicalPayload) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonicalPayload.getBytes(
                                    StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_SHA256_UNAVAILABLE", impossible);
        }
    }

    private static IllegalArgumentException invalidPayload(
            RuntimeException cause) {
        return new IllegalArgumentException(
                "ACCESS_INVALIDATION_EVENT_PAYLOAD_INVALID", cause);
    }

    public record ValidatedPayload(
            String canonicalPayload, String payloadDigest) {}

    private static Map<String, Object> sourceVector(
            AccessInvalidationFact fact) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceId", fact.sourceVector().sourceId());
        value.put("sourceVersion", fact.sourceVector().sourceVersion());
        value.put(
                "sourceWatermark",
                fact.sourceVector().sourceWatermark());
        value.put(
                "dependencyVector",
                fact.sourceVector().dependencyVector().stream()
                        .map(item -> {
                            Map<String, Object> dependency =
                                    new LinkedHashMap<>();
                            dependency.put("feedId", item.feedId());
                            dependency.put(
                                    "partitionId", item.partitionId());
                            dependency.put("watermark", item.watermark());
                            return dependency;
                        })
                        .toList());
        return value;
    }

    private static Map<String, Object> subjectSnapshot(
            AccessInvalidationFact fact) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(
                "subjectToken",
                fact.subjectSnapshot().subjectToken());
        value.put("scopeToken", fact.subjectSnapshot().scopeToken());
        value.put(
                "objectDigest",
                "sha256:" + fact.subjectSnapshot().objectDigest());
        value.put(
                "tokenizationProfileVersion",
                fact.subjectSnapshot().tokenizationProfileVersion());
        return value;
    }

    private static Map<String, Object> authorizationSnapshot(
            AccessInvalidationFact fact) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(
                "currentState",
                wire(fact.authorizationSnapshot().currentState()));
        value.put(
                "accountActive",
                fact.authorizationSnapshot().accountActive());
        value.put(
                "r1EmploymentValid",
                fact.authorizationSnapshot().r1EmploymentValid());
        value.put(
                "collegeActive",
                fact.authorizationSnapshot().collegeActive());
        value.put(
                "relationEffective",
                fact.authorizationSnapshot().relationEffective());
        value.put(
                "policyVersion",
                fact.authorizationSnapshot().policyVersion());
        return value;
    }

    private static Map<String, Object> retention(
            AccessInvalidationFact fact) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(
                "classification",
                fact.retention().classification());
        value.put(
                "retentionScheduleVersion",
                fact.retention().retentionScheduleVersion());
        value.put(
                "retainUntil",
                fact.retention().retainUntil().toString());
        value.put("legalHold", fact.retention().legalHold());
        return value;
    }

    private static String wire(Enum<?> value) {
        return value.name().toLowerCase().replace('_', '-');
    }
}
