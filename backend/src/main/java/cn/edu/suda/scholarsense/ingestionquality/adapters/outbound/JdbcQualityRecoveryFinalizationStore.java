package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryFinalizationCommit;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryFinalizationContext;
import cn.edu.suda.scholarsense.ingestionquality.application.QualityRecoveryFinalizationStorePort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exact JSON decoder for the V21 finalization owner functions. */
public final class JdbcQualityRecoveryFinalizationStore
        implements QualityRecoveryFinalizationStorePort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcQualityRecoveryFinalizationStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public Optional<QualityRecoveryFinalizationContext> load(UUID recoveryId) {
        String value = jdbc.queryForObject("""
                select ingestion_quality.iq_load_quality_finalization_context(?)::text
                """, String.class, recoveryId);
        if (value == null) return Optional.empty();
        try {
            JsonNode root = json.readTree(value);
            if (!root.isObject()) throw invalid();
            return Optional.of(context(root));
        } catch (JacksonException malformed) {
            throw invalid();
        }
    }

    @Override
    public QualityRecoveryFinalizationContext bindApproval(Map<String, Object> binding) {
        return context(call("""
                select ingestion_quality.iq_bind_quality_finalization_approval(?::jsonb)::text
                """, encode(binding)));
    }

    @Override
    public Optional<QualityRecoveryFinalizationCommit> findReplay(
            String idempotencyKeyDigest,
            String clientCommandDigest,
            UUID recoveryId,
            String finalObservationWatermark) {
        String value = jdbc.queryForObject("""
                select ingestion_quality.iq_find_quality_finalization_replay(?,?,?,?)::text
                """, String.class, idempotencyKeyDigest, clientCommandDigest,
                recoveryId, finalObservationWatermark);
        if (value == null) return Optional.empty();
        try {
            JsonNode root = json.readTree(value);
            if (!root.isObject()) throw invalid();
            return Optional.of(commit(root));
        } catch (JacksonException malformed) {
            throw invalid();
        }
    }

    @Override
    public QualityRecoveryFinalizationCommit execute(
            String idempotencyKeyDigest, Map<String, Object> command) {
        LinkedHashMap<String, Object> bound = new LinkedHashMap<>(command);
        bound.put("commandDigest", canonicalDigest(bound));
        JsonNode root = call("""
                select ingestion_quality.iq_execute_quality_finalization(?,?::jsonb)::text
                """, idempotencyKeyDigest, encode(bound));
        return commit(root);
    }

    private QualityRecoveryFinalizationCommit commit(JsonNode root) {
        return new QualityRecoveryFinalizationCommit(
                uuid(root, "recoveryId"), number(root, "generation"),
                text(root, "eligibilityStatus"), text(root, "episodeStatus"),
                uuid(root, "taskId"), text(root, "taskStatus"),
                number(root, "taskVersion"), instant(root, "recoveryCompletedAt"),
                text(root, "windowOutcomesDigest"), text(root, "ownerResultDigest"),
                text(root, "deliveryStatus"), uuid(root, "executionJti"),
                uuid(root, "confirmationOutboxEventId"), text(root, "traceId"));
    }

    private QualityRecoveryFinalizationContext context(JsonNode root) {
        ArrayList<QualityRecoveryFinalizationContext.AffectedRule> rules = new ArrayList<>();
        JsonNode values = root.required("affectedRules");
        if (!values.isArray()) throw invalid();
        for (JsonNode value : values) {
            rules.add(new QualityRecoveryFinalizationContext.AffectedRule(
                    text(value, "ruleId"), text(value, "ruleVersion"),
                    text(value, "ruleVersionDigest")));
        }
        return new QualityRecoveryFinalizationContext(
                uuid(root, "recoveryId"), number(root, "recoveryVersion"),
                uuid(root, "taskId"), number(root, "taskVersion"),
                uuid(root, "episodeId"), number(root, "episodeVersion"),
                number(root, "generation"), text(root, "sourceId"),
                text(root, "dependencyId"), text(root, "taskStatus"),
                bool(root, "episodeActive"), text(root, "observationStatus"),
                number(root, "observationVersion"), text(root, "observationDecisionDigest"),
                text(root, "finalObservationWatermark"), text(root, "policyVersion"),
                text(root, "policyDigest"), text(root, "memberSetDigest"),
                text(root, "watermarksDigest"), text(root, "finalPreviewDigest"),
                text(root, "finalizationState"), optionalUuid(root, "approvalId"),
                optionalNumber(root, "approvalVersion"),
                optionalText(root, "approvalReceiptDigest"),
                optionalText(root, "checkerSetDigest"),
                optionalText(root, "ownerBindingSetDigest"),
                optionalText(root, "checkerPersonSetDigest"),
                text(root, "authorizationContextDigest"),
                text(root, "authenticationStateDigest"),
                number(root, "authorizationGeneration"),
                optionalText(root, "makerPrincipalDigest"),
                optionalText(root, "requestDigest"), text(root, "scopeDigest"),
                text(root, "impactScopeDigest"), rules, text(root, "traceId"));
    }

    private JsonNode call(String sql, Object... args) {
        try {
            JsonNode root = json.readTree(Objects.requireNonNull(
                    jdbc.queryForObject(sql, String.class, args)));
            if (!root.isObject()) throw invalid();
            return root;
        } catch (JacksonException malformed) {
            throw invalid();
        }
    }

    private String encode(Map<String, Object> value) {
        try {
            return json.writeValueAsString(new LinkedHashMap<>(value));
        } catch (JacksonException malformed) {
            throw invalid();
        }
    }

    private String canonicalDigest(Map<String, Object> value) {
        try {
            return CanonicalCatalogJson.digest(json,
                    json.readTree(json.writeValueAsString(value)));
        } catch (JacksonException malformed) {
            throw invalid();
        }
    }

    private static String text(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid();
        return value.asText();
    }

    private static String optionalText(JsonNode root, String name) {
        JsonNode value = root.get(name);
        return value == null || value.isNull() ? null : text(root, name);
    }

    private static long number(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isIntegralNumber()) throw invalid();
        return value.asLong();
    }

    private static Long optionalNumber(JsonNode root, String name) {
        JsonNode value = root.get(name);
        return value == null || value.isNull() ? null : number(root, name);
    }

    private static boolean bool(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isBoolean()) throw invalid();
        return value.asBoolean();
    }

    private static UUID uuid(JsonNode root, String name) {
        try {
            UUID value = UUID.fromString(text(root, name));
            if (value.version() != 7 || value.variant() != 2) throw invalid();
            return value;
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
    }

    private static UUID optionalUuid(JsonNode root, String name) {
        String value = optionalText(root, name);
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant instant(JsonNode root, String name) {
        Instant value = Instant.parse(text(root, name));
        if (value.getNano() % 1_000 != 0) throw invalid();
        return value;
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID");
    }
}
