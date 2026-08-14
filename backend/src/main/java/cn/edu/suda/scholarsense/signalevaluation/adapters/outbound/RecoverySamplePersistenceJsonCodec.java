package cn.edu.suda.scholarsense.signalevaluation.adapters.outbound;

import cn.edu.suda.scholarsense.signalevaluation.application.RecoverySampleComputationResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exact PII-free replay projection owned by signal-evaluation. */
final class RecoverySamplePersistenceJsonCodec {
    private final ObjectMapper json;

    RecoverySamplePersistenceJsonCodec(ObjectMapper json) {
        this.json = java.util.Objects.requireNonNull(json);
    }

    String write(RecoverySampleComputationResponse value) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("availability", value.availability().name().toLowerCase());
        root.put("error", value.error() == null ? null : value.error().name());
        root.put("retryable", value.retryable());
        root.put("traceId", value.traceId());
        root.put("result", value.result() == null ? null : result(value.result()));
        try {
            return json.writeValueAsString(root);
        } catch (RuntimeException failure) {
            throw invalid(failure);
        }
    }

    RecoverySampleComputationResponse read(String encoded) {
        try {
            JsonNode root = json.readTree(encoded);
            String traceId = text(root, "traceId");
            if ("available".equals(text(root, "availability"))) {
                JsonNode result = root.required("result");
                ArrayList<RecoverySampleComputationResponse.Stratum> strata = new ArrayList<>();
                result.required("strata").forEach(value -> strata.add(
                        new RecoverySampleComputationResponse.Stratum(
                                text(value, "stratumCode"), number(value, "populationCount"),
                                integer(value, "selectedCount"), integer(value, "mismatchCount"),
                                text(value, "summaryDigest"))));
                return RecoverySampleComputationResponse.available(
                        new RecoverySampleComputationResponse.Result(
                                text(result, "providerVersion"), text(result, "selectionSeed"),
                                number(result, "populationCount"),
                                integer(result, "selectedCount"), strata,
                                text(result, "strataSummaryDigest"),
                                text(result, "expectedDigest"), text(result, "actualDigest"),
                                integer(result, "mismatchCount"),
                                Instant.parse(text(result, "completedAt")), traceId));
            }
            return RecoverySampleComputationResponse.failure(
                    RecoverySampleComputationResponse.Error.valueOf(text(root, "error")),
                    root.required("retryable").asBoolean(), traceId);
        } catch (RuntimeException failure) {
            throw invalid(failure);
        }
    }

    JsonNode tree(String encoded) {
        try {
            return json.readTree(encoded);
        } catch (RuntimeException failure) {
            throw invalid(failure);
        }
    }

    String string(JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (RuntimeException failure) {
            throw invalid(failure);
        }
    }

    private static Map<String, Object> result(RecoverySampleComputationResponse.Result value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providerVersion", value.providerVersion());
        result.put("selectionSeed", value.selectionSeed());
        result.put("populationCount", value.populationCount());
        result.put("selectedCount", value.selectedCount());
        result.put("strata", value.strata().stream().map(stratum -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stratumCode", stratum.stratumCode());
            item.put("populationCount", stratum.populationCount());
            item.put("selectedCount", stratum.selectedCount());
            item.put("mismatchCount", stratum.mismatchCount());
            item.put("summaryDigest", stratum.summaryDigest());
            return item;
        }).toList());
        result.put("strataSummaryDigest", value.strataSummaryDigest());
        result.put("expectedDigest", value.expectedDigest());
        result.put("actualDigest", value.actualDigest());
        result.put("mismatchCount", value.mismatchCount());
        result.put("completedAt", value.completedAt().toString());
        result.put("traceId", value.traceId());
        return result;
    }

    static String text(JsonNode root, String name) {
        JsonNode value = root.required(name);
        if (!value.isTextual()) throw invalid(null);
        return value.asText();
    }

    static long number(JsonNode root, String name) {
        JsonNode value = root.required(name);
        if (!value.isIntegralNumber()) throw invalid(null);
        return value.asLong();
    }

    static int integer(JsonNode root, String name) {
        long value = number(root, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw invalid(null);
        return (int) value;
    }

    private static IllegalArgumentException invalid(Throwable failure) {
        return new IllegalArgumentException("RECOVERY_SAMPLE_PERSISTED_VALUE_INVALID", failure);
    }
}
