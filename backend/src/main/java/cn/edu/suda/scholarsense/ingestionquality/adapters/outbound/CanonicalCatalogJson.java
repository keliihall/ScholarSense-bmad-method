package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class CanonicalCatalogJson {
    private CanonicalCatalogJson() {}

    static String digest(ObjectMapper json, JsonNode node) {
        return digest(json, node, Set.of());
    }

    static byte[] canonicalBytes(ObjectMapper json, JsonNode node) {
        return canonicalBytes(json, javaValue(node, true, Set.of()));
    }

    static byte[] canonicalBytesOnly(ObjectMapper json, JsonNode node, Set<String> includedFields) {
        if (node == null || !node.isObject() || includedFields == null || includedFields.isEmpty()) {
            throw new IllegalArgumentException("INGESTION_QUALITY_FROZEN_JSON_INVALID");
        }
        Map<String, Object> canonical = new TreeMap<>();
        for (String field : includedFields) {
            canonical.put(field, javaValue(node.required(field), false, Set.of()));
        }
        return canonicalBytes(json, canonical);
    }

    static String digestWithout(ObjectMapper json, JsonNode node, String field) {
        return digest(json, node, Set.of(field));
    }

    private static String digest(ObjectMapper json, JsonNode node, Set<String> omittedRootFields) {
        try {
            Object canonical = javaValue(node, true, omittedRootFields);
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalBytes(json, canonical)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] canonicalBytes(ObjectMapper json, Object canonical) {
        try {
            return json.writeValueAsBytes(canonical);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("INGESTION_QUALITY_FROZEN_JSON_INVALID", failure);
        }
    }

    private static Object javaValue(JsonNode node, boolean root, Set<String> omittedRootFields) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(item -> values.add(javaValue(item, false, Set.of())));
            return values;
        }
        if (node.isObject()) {
            Map<String, Object> values = new TreeMap<>();
            node.forEachEntry((key, value) -> {
                if (!root || !omittedRootFields.contains(key)) {
                    values.put(key, javaValue(value, false, Set.of()));
                }
            });
            return values;
        }
        throw new IllegalArgumentException("INGESTION_QUALITY_FROZEN_JSON_INVALID");
    }
}
