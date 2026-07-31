package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.CheckpointKey;
import cn.edu.suda.scholarsense.identityaccess.application.IdentitySyncException;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityFullSnapshot;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilityReconciliationService;
import cn.edu.suda.scholarsense.identityaccess.application.ResponsibilitySnapshotEntry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Strict normalizer for a signed, sealed, complete responsibility snapshot. */
public final class ResponsibilityFullSnapshotNormalizer {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "$schema",
            "schemaVersion",
            "contractVersion",
            "sourceId",
            "feedId",
            "consumerProjection",
            "snapshotId",
            "businessDate",
            "cutoffAt",
            "sourceVersion",
            "throughWatermark",
            "supportingIdentityOrgWatermarks",
            "sealed",
            "complete",
            "expectedCount",
            "canonicalDigest",
            "signatureDigest",
            "partitions",
            "records",
            "traceId");
    private static final Set<String> RECORD_FIELDS = Set.of(
            "relationRefToken",
            "studentEquivalenceDomain",
            "recordVersion",
            "payloadDigest");

    private final ObjectMapper json;

    public ResponsibilityFullSnapshotNormalizer(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json);
    }

    public ResponsibilityFullSnapshot normalize(
            byte[] body,
            String detachedSignature,
            boolean signatureVerified,
            CheckpointKey requestedKey,
            LocalDate requestedDate,
            String traceId) {
        try {
            JsonNode root = json.readTree(body);
            requireExact(root, ROOT_FIELDS);
            CheckpointKey key = new CheckpointKey(
                    text(root, "sourceId"),
                    text(root, "feedId"),
                    requestedKey.partitionId(),
                    text(root, "consumerProjection"));
            if (!key.equals(requestedKey)
                    || !requestedDate.equals(LocalDate.parse(
                            text(root, "businessDate")))
                    || !traceId.equals(text(root, "traceId"))
                    || !"RESPONSIBILITY-SNAPSHOT-1.0.0".equals(
                            text(root, "schemaVersion"))
                    || !"RESPONSIBILITY-AUTHORITY-1.0.0".equals(
                            text(root, "contractVersion"))) {
                throw invalid(
                        "RESPONSIBILITY_SOURCE_CONTRACT_UNAPPROVED");
            }
            String signatureDigest =
                    ResponsibilityAuthorityNormalizer.digest(
                            detachedSignature.getBytes(
                                    StandardCharsets.UTF_8));
            if (!signatureDigest.equals(stripDigest(
                    text(root, "signatureDigest")))) {
                throw invalid(
                        "RESPONSIBILITY_SNAPSHOT_SIGNATURE_INVALID");
            }
            boolean sealed = booleanValue(root, "sealed");
            boolean complete = booleanValue(root, "complete");
            if (!sealed) {
                throw invalid(
                        "RESPONSIBILITY_SNAPSHOT_UNSEALED");
            }
            if (!complete) {
                throw invalid(
                        "RESPONSIBILITY_SNAPSHOT_PARTIAL");
            }
            List<String> partitions = strings(
                    root.required("partitions"));
            if (!partitions.contains(requestedKey.partitionId())) {
                throw invalid(
                        "RESPONSIBILITY_SNAPSHOT_PARTITION_MISSING");
            }
            List<ResponsibilitySnapshotEntry> entries =
                    new ArrayList<>();
            JsonNode records = root.required("records");
            if (!records.isArray()
                    || records.size() > 1_000_000) {
                throw invalid(
                        "RESPONSIBILITY_SNAPSHOT_COUNT_MISMATCH");
            }
            records.forEach(record -> {
                requireExact(record, RECORD_FIELDS);
                String relationRef =
                        text(record, "relationRefToken");
                entries.add(new ResponsibilitySnapshotEntry(
                        relationRef,
                        stripDigest(text(
                                record,
                                "studentEquivalenceDomain")),
                        longValue(record, "recordVersion"),
                        stripDigest(text(
                                record, "payloadDigest")),
                        false,
                        false));
            });
            long expectedCount =
                    longValue(root, "expectedCount");
            if (expectedCount != entries.size()) {
                throw invalid(
                        "RESPONSIBILITY_SNAPSHOT_COUNT_MISMATCH");
            }
            String canonicalDigest =
                    stripDigest(text(root, "canonicalDigest"));
            if (!canonicalDigest.equals(
                    ResponsibilityReconciliationService.digest(
                            entries))) {
                throw invalid(
                        "RESPONSIBILITY_SNAPSHOT_DIGEST_MISMATCH");
            }
            return new ResponsibilityFullSnapshot(
                    UUID.fromString(text(root, "snapshotId")),
                    key,
                    text(root, "schemaVersion"),
                    text(root, "contractVersion"),
                    requestedDate,
                    Instant.parse(text(root, "cutoffAt")),
                    longValue(root, "sourceVersion"),
                    longValue(root, "throughWatermark"),
                    dependencies(root.required(
                            "supportingIdentityOrgWatermarks")),
                    true,
                    true,
                    partitions,
                    expectedCount,
                    canonicalDigest,
                    signatureDigest,
                    signatureVerified,
                    entries,
                    traceId);
        } catch (IdentitySyncException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw invalid("RESPONSIBILITY_SNAPSHOT_INVALID");
        }
    }

    private static Map<String, Long> dependencies(JsonNode node) {
        if (!node.isObject()) {
            throw invalid(
                    "RESPONSIBILITY_DEPENDENCY_VECTOR_INVALID");
        }
        Map<String, Long> result = new LinkedHashMap<>();
        node.forEachEntry((key, value) -> {
            if (!value.isIntegralNumber()
                    || value.asLong() < 0) {
                throw invalid(
                        "RESPONSIBILITY_DEPENDENCY_VECTOR_INVALID");
            }
            result.put(key, value.asLong());
        });
        return result;
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            throw invalid(
                    "RESPONSIBILITY_SNAPSHOT_PARTITION_MISSING");
        }
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual()
                    || !value.asText().matches(
                            "[a-z0-9][a-z0-9-]{0,63}")) {
                throw invalid(
                        "RESPONSIBILITY_SNAPSHOT_PARTITION_MISSING");
            }
            values.add(value.asText());
        });
        return List.copyOf(values);
    }

    private static void requireExact(
            JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject()) {
            throw invalid("RESPONSIBILITY_SNAPSHOT_INVALID");
        }
        Set<String> actual = new java.util.HashSet<>();
        node.forEachEntry((key, value) -> actual.add(key));
        if (!actual.equals(fields)) {
            throw invalid("RESPONSIBILITY_SNAPSHOT_INVALID");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid("RESPONSIBILITY_SNAPSHOT_INVALID");
        }
        return value.asText();
    }

    private static long longValue(JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isIntegralNumber()) {
            throw invalid("RESPONSIBILITY_SNAPSHOT_INVALID");
        }
        return value.asLong();
    }

    private static boolean booleanValue(
            JsonNode node, String field) {
        JsonNode value = node.required(field);
        if (!value.isBoolean()) {
            throw invalid("RESPONSIBILITY_SNAPSHOT_INVALID");
        }
        return value.asBoolean();
    }

    private static String stripDigest(String value) {
        if (value == null
                || !value.matches("sha256:[0-9a-f]{64}")) {
            throw invalid("RESPONSIBILITY_SNAPSHOT_INVALID");
        }
        return value.substring("sha256:".length());
    }

    private static IdentitySyncException invalid(String code) {
        return new IdentitySyncException(code);
    }
}
