package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.EncryptedSecret;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.NormalizedResponsibilityBatch;
import cn.edu.suda.scholarsense.identityaccess.application.PseudonymizationPort;
import cn.edu.suda.scholarsense.identityaccess.domain.AuthoritativeResponsibilityRelation;
import cn.edu.suda.scholarsense.identityaccess.domain.EffectiveInterval;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStatus;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityStudentSourceReference;
import cn.edu.suda.scholarsense.identityaccess.domain.ResponsibilityType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Converts provider JSON into the provider-neutral responsibility batch contract. */
public final class ResponsibilityAuthorityNormalizer {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "$schema",
            "schemaVersion",
            "contractVersion",
            "sourceId",
            "feedId",
            "partitionId",
            "consumerProjection",
            "batchId",
            "sourceVersion",
            "fromWatermark",
            "toWatermark",
            "supportingIdentityOrgWatermarks",
            "sourceVisibleAt",
            "observedAt",
            "traceId",
            "correlationId",
            "signatureDigest",
            "records");
    private static final Set<String> RECORD_FIELDS = Set.of("eventId", "payload");
    private static final Set<String> PAYLOAD_FIELDS = Set.of(
            "relationRefToken",
            "studentSourceRef",
            "counselorAccountExternalRef",
            "collegeOrganizationExternalRef",
            "responsibilityType",
            "status",
            "effectiveFrom",
            "effectiveTo",
            "recordVersion",
            "payloadDigest");
    private static final Set<String> STUDENT_FIELDS =
            Set.of(
                    "purposeCode",
                    "keyVersion",
                    "tokenValue",
                    "digest",
                    "equivalenceDomain");

    private final ObjectMapper json;
    private final PseudonymizationPort pseudonyms;

    public ResponsibilityAuthorityNormalizer(
            ObjectMapper json, PseudonymizationPort pseudonyms) {
        this.json = java.util.Objects.requireNonNull(json);
        this.pseudonyms = java.util.Objects.requireNonNull(pseudonyms);
    }

    public NormalizedResponsibilityBatch normalize(
            byte[] body,
            String detachedSignature,
            EncryptedSecret secured,
            boolean signatureVerified,
            CheckpointKey requestedKey,
            long afterWatermark,
            Long throughWatermark,
            String traceId,
            Instant observedAt) {
        try {
            JsonNode root = json.readTree(body);
            requireExact(root, ROOT_FIELDS);
            CheckpointKey key = new CheckpointKey(
                    text(root, "sourceId"),
                    text(root, "feedId"),
                    text(root, "partitionId"),
                    text(root, "consumerProjection"));
            if (!key.equals(requestedKey)
                    || !"responsibility".equals(key.consumerProjection())
                    || !"RESPONSIBILITY-BATCH-1.0.0".equals(
                            text(root, "schemaVersion"))
                    || !"RESPONSIBILITY-AUTHORITY-1.0.0".equals(
                            text(root, "contractVersion"))
                    || longValue(root, "fromWatermark") != afterWatermark
                    || throughWatermark != null
                            && longValue(root, "toWatermark") != throughWatermark
                    || !traceId.equals(text(root, "traceId"))) {
                throw invalid("RESPONSIBILITY_SOURCE_CONTRACT_UNAPPROVED");
            }
            String signatureDigest = digest(
                    detachedSignature.getBytes(StandardCharsets.UTF_8));
            if (!signatureDigest.equals(
                    stripDigest(text(root, "signatureDigest")))) {
                throw invalid(
                        "RESPONSIBILITY_SOURCE_SIGNATURE_DIGEST_INVALID");
            }
            long sourceVersion = longValue(root, "sourceVersion");
            long fromWatermark = longValue(root, "fromWatermark");
            long toWatermark = longValue(root, "toWatermark");
            Instant sourceVisibleAt =
                    Instant.parse(text(root, "sourceVisibleAt"));
            Instant providerObservedAt =
                    Instant.parse(text(root, "observedAt"));
            if (providerObservedAt.isBefore(sourceVisibleAt)
                    || observedAt.isBefore(sourceVisibleAt)) {
                throw invalid("RESPONSIBILITY_SOURCE_TIME_INVALID");
            }
            Map<String, Long> dependencies = dependencyVector(
                    root.required("supportingIdentityOrgWatermarks"));
            JsonNode records = root.required("records");
            if (!records.isArray()
                    || records.size() > 1000
                    || (toWatermark == fromWatermark && !records.isEmpty())
                    || (toWatermark > fromWatermark && records.isEmpty())
                    || toWatermark < fromWatermark) {
                throw invalid("RESPONSIBILITY_SOURCE_PAYLOAD_INVALID");
            }
            List<AuthoritativeResponsibilityRelation> relations =
                    new ArrayList<>();
            records.forEach(record -> relations.add(parseRelation(
                    record, key.sourceId(), sourceVersion, toWatermark)));
            return new NormalizedResponsibilityBatch(
                    UUID.fromString(text(root, "batchId")),
                    key,
                    text(root, "schemaVersion"),
                    text(root, "contractVersion"),
                    sourceVersion,
                    fromWatermark,
                    toWatermark,
                    dependencies,
                    sourceVisibleAt,
                    observedAt,
                    traceId,
                    digest(body),
                    signatureDigest,
                    signatureVerified,
                    secured.ciphertext(),
                    secured.wrappedDataKey(),
                    secured.nonce(),
                    secured.keyRef(),
                    secured.keyVersion(),
                    relations);
        } catch (IdentitySyncException failure) {
            throw failure;
        } catch (IllegalArgumentException invalidDomain) {
            String code = invalidDomain.getMessage();
            if (code != null
                    && code.matches("RESPONSIBILITY_[A-Z0-9_]{3,120}")) {
                throw new IdentitySyncException(code);
            }
            throw invalid("RESPONSIBILITY_SOURCE_PAYLOAD_INVALID");
        } catch (RuntimeException malformed) {
            throw invalid("RESPONSIBILITY_SOURCE_PAYLOAD_INVALID");
        }
    }

    private AuthoritativeResponsibilityRelation parseRelation(
            JsonNode record,
            String sourceId,
            long sourceVersion,
            long sourceWatermark) {
        requireExact(record, RECORD_FIELDS);
        JsonNode payload = record.required("payload");
        requireExact(payload, PAYLOAD_FIELDS);
        JsonNode student = payload.required("studentSourceRef");
        requireExact(student, STUDENT_FIELDS);
        String studentDigest = stripDigest(text(student, "digest"));
        String payloadDigest = stripDigest(text(payload, "payloadDigest"));
        if (!payloadDigest.equals(digest(canonicalWithout(
                payload, "payloadDigest")))) {
            throw invalid("RESPONSIBILITY_SOURCE_PAYLOAD_DIGEST_INVALID");
        }
        String effectiveTo = nullableText(payload, "effectiveTo");
        return new AuthoritativeResponsibilityRelation(
                UUID.fromString(text(record, "eventId")),
                sourceId,
                text(payload, "relationRefToken"),
                new ResponsibilityStudentSourceReference(
                        text(student, "purposeCode"),
                        text(student, "keyVersion"),
                        text(student, "tokenValue"),
                        studentDigest,
                        stripDigest(text(student, "equivalenceDomain"))),
                identityExternalRefDigest(
                        sourceId,
                        text(payload, "counselorAccountExternalRef")),
                identityExternalRefDigest(
                        sourceId,
                        text(payload, "collegeOrganizationExternalRef")),
                switch (text(payload, "responsibilityType")) {
                    case "primary" -> ResponsibilityType.PRIMARY;
                    case "secondary" -> ResponsibilityType.SECONDARY;
                    default -> throw invalid(
                            "RESPONSIBILITY_TYPE_INVALID");
                },
                switch (text(payload, "status")) {
                    case "active" -> ResponsibilityStatus.ACTIVE;
                    case "inactive" -> ResponsibilityStatus.INACTIVE;
                    default -> throw invalid(
                            "RESPONSIBILITY_STATUS_INVALID");
                },
                new EffectiveInterval(
                        Instant.parse(text(payload, "effectiveFrom")),
                        effectiveTo == null ? null : Instant.parse(effectiveTo)),
                sourceVersion,
                sourceWatermark,
                longValue(payload, "recordVersion"),
                sourceVersion,
                payloadDigest);
    }

    private String identityExternalRefDigest(
            String sourceId, String providerExternalReference) {
        String token = pseudonyms.pseudonymize(
                "identity-external-ref",
                sourceId + "\0" + providerExternalReference);
        if (token == null
                || !token.matches(
                        "external_v1_k[0-9]+_[0-9a-f]{64}")) {
            throw invalid(
                    "RESPONSIBILITY_IDENTITY_REFERENCE_BINDING_INVALID");
        }
        return token.substring(token.length() - 64);
    }

    private static Map<String, Long> dependencyVector(JsonNode value) {
        if (!value.isObject()) {
            throw invalid("RESPONSIBILITY_DEPENDENCY_VECTOR_INVALID");
        }
        Map<String, Long> result = new LinkedHashMap<>();
        value.forEachEntry((key, node) -> {
            if (!node.isIntegralNumber() || node.asLong() < 0) {
                throw invalid("RESPONSIBILITY_DEPENDENCY_VECTOR_INVALID");
            }
            result.put(key, node.asLong());
        });
        return result;
    }

    private static void requireExact(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) {
            throw invalid("RESPONSIBILITY_SOURCE_PAYLOAD_INVALID");
        }
        Set<String> actual = new java.util.HashSet<>();
        node.forEachEntry((key, ignored) -> actual.add(key));
        if (!actual.equals(expected)) {
            throw invalid("RESPONSIBILITY_SOURCE_PAYLOAD_INVALID");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid("RESPONSIBILITY_SOURCE_PAYLOAD_INVALID");
        }
        return value.asText();
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid("RESPONSIBILITY_SOURCE_PAYLOAD_INVALID");
        }
        return value.asText();
    }

    private static long longValue(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isIntegralNumber()) {
            throw invalid("RESPONSIBILITY_SOURCE_PAYLOAD_INVALID");
        }
        return value.asLong();
    }

    private static String stripDigest(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw invalid("RESPONSIBILITY_SOURCE_DIGEST_INVALID");
        }
        return value.substring("sha256:".length());
    }

    private static byte[] canonicalWithout(JsonNode object, String excluded) {
        StringBuilder result = new StringBuilder();
        appendCanonical(object, result, excluded, true);
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendCanonical(
            JsonNode value,
            StringBuilder result,
            String excluded,
            boolean atRoot) {
        if (value.isObject()) {
            java.util.TreeMap<String, JsonNode> fields =
                    new java.util.TreeMap<>();
            value.forEachEntry(fields::put);
            result.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> field : fields.entrySet()) {
                if (atRoot && excluded.equals(field.getKey())) {
                    continue;
                }
                if (!first) {
                    result.append(',');
                }
                result.append('"').append(field.getKey()).append('"')
                        .append(':');
                appendCanonical(field.getValue(), result, excluded, false);
                first = false;
            }
            result.append('}');
        } else if (value.isArray()) {
            result.append('[');
            boolean first = true;
            for (JsonNode item : value) {
                if (!first) {
                    result.append(',');
                }
                appendCanonical(item, result, excluded, false);
                first = false;
            }
            result.append(']');
        } else {
            result.append(value.toString());
        }
    }

    static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IdentitySyncException invalid(String code) {
        return new IdentitySyncException(code);
    }
}
