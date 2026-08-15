package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryObservationCandidate;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryObservationClaim;
import cn.edu.suda.scholarsense.ingestionquality.application.RecoveryObservationWorkPort;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservation;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationDecision;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationEvidence;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationEvidenceStatus;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationFact;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationFence;
import cn.edu.suda.scholarsense.ingestionquality.domain.RecoveryObservationSourceClass;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** JDBC implementation for the dedicated V21 observation job and fencing protocol. */
public final class JdbcRecoveryObservationWork implements RecoveryObservationWorkPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcRecoveryObservationWork(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public List<RecoveryObservationCandidate> findClaimable(int limit, Instant trustedNow) {
        return jdbc.query("""
                select * from ingestion_quality.iq_find_claimable_recovery_observation_jobs(?,?)
                """, (row, ignored) -> new RecoveryObservationCandidate(
                row.getObject("job_id", UUID.class)), limit, Timestamp.from(trustedNow));
    }

    @Override
    public RecoveryObservationClaim claim(
            UUID jobId, String workerDigest, Instant trustedNow, Duration leaseDuration) {
        long seconds = leaseDuration.toSeconds();
        if (seconds < 1 || seconds > 300 || !leaseDuration.equals(Duration.ofSeconds(seconds))) {
            throw invalid();
        }
        JsonNode root = callJson("""
                select ingestion_quality.iq_claim_recovery_observation_job(?,?,?,?)::text
                """, jobId, workerDigest, Timestamp.from(trustedNow), Math.toIntExact(seconds));
        String policyDigest = text(root, "policyDigest");
        String memberSetDigest = text(root, "memberSetDigest");
        String watermarksDigest = text(root, "watermarksDigest");
        JsonNode currentFence = root.get("currentFence");
        if (currentFence == null || !currentFence.isObject()) throw invalid();
        RecoveryObservation observation = new RecoveryObservation(
                uuid(root, "recoveryId"), number(root, "generation"),
                text(root, "sourceId"), sourceClass(text(root, "sourceClass")),
                instant(root, "recoveringStartedAt"), text(root, "policyVersion"),
                policyDigest, memberSetDigest, watermarksDigest, facts(root),
                evidence(root));
        return new RecoveryObservationClaim(
                uuid(root, "jobId"), number(root, "leaseGeneration"),
                Math.toIntExact(number(root, "attemptNumber")), observation,
                new RecoveryObservationFence(
                        text(currentFence, "policyVersion"),
                        text(currentFence, "policyDigest"),
                        text(currentFence, "memberSetDigest"),
                        text(currentFence, "watermarksDigest")),
                bool(root, "allAffectedEligibilitiesRecovering"));
    }

    @Override
    public boolean isLeaseCurrent(UUID jobId, long generation, Instant trustedNow) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select ingestion_quality.iq_is_recovery_observation_lease_current(?,?,?)
                """, Boolean.class, jobId, generation, Timestamp.from(trustedNow)));
    }

    @Override
    public boolean complete(
            UUID jobId, long generation,
            RecoveryObservationDecision decision, Instant trustedNow) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select ingestion_quality.iq_finalize_recovery_observation_job(
                    ?,?,?::jsonb,?)
                """, Boolean.class, jobId, generation,
                encode(decision(jobId, decision)), Timestamp.from(trustedNow)));
    }

    @Override
    public boolean retry(
            UUID jobId, long generation, Instant nextAttemptAt, Instant trustedNow) {
        return release(jobId, generation, "retry-scheduled", nextAttemptAt, trustedNow);
    }

    @Override
    public boolean yield(
            UUID jobId, long generation, Instant nextAttemptAt, Instant trustedNow) {
        return release(jobId, generation, "yielded", nextAttemptAt, trustedNow);
    }

    @Override
    public boolean fail(UUID jobId, long generation, Instant trustedNow) {
        return release(jobId, generation, "failed", null, trustedNow);
    }

    private boolean release(
            UUID jobId, long generation, String outcome,
            Instant nextAttemptAt, Instant trustedNow) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select ingestion_quality.iq_release_recovery_observation_job(?,?,?,?,?)
                """, Boolean.class, jobId, generation, outcome,
                nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt),
                Timestamp.from(trustedNow)));
    }

    private List<RecoveryObservationFact> facts(JsonNode root) {
        JsonNode facts = root.get("facts");
        if (facts == null || !facts.isArray()) throw invalid();
        ArrayList<RecoveryObservationFact> result = new ArrayList<>();
        for (JsonNode fact : facts) {
            if ("verified-quality-failure".equals(text(fact, "factType"))) {
                result.add(fact(fact, RecoveryObservationFact.Stage.VERIFIED_QUALITY_FAILURE));
            } else if ("published-pair".equals(text(fact, "factType"))) {
                result.add(fact(fact, RecoveryObservationFact.Stage.ASSESSED_PASSED));
                result.add(fact(fact, RecoveryObservationFact.Stage.PUBLISHED));
            } else {
                throw invalid();
            }
        }
        return result;
    }

    private static RecoveryObservationEvidence evidence(JsonNode root) {
        JsonNode value = root.get("evidence");
        if (value == null || !value.isObject()) throw invalid();
        return new RecoveryObservationEvidence(
                evidenceStatus(text(value, "requiredMembers")),
                evidenceStatus(text(value, "reconciliation")),
                evidenceStatus(text(value, "sample")),
                evidenceStatus(text(value, "sloFreshness")));
    }

    private static RecoveryObservationEvidenceStatus evidenceStatus(String value) {
        return switch (value) {
            case "passed" -> RecoveryObservationEvidenceStatus.PASSED;
            case "verified-failed" -> RecoveryObservationEvidenceStatus.VERIFIED_FAILED;
            case "unavailable" -> RecoveryObservationEvidenceStatus.UNAVAILABLE;
            case "unknown" -> RecoveryObservationEvidenceStatus.UNKNOWN;
            default -> throw invalid();
        };
    }

    private RecoveryObservationFact fact(
            JsonNode fact, RecoveryObservationFact.Stage stage) {
        return new RecoveryObservationFact(
                uuid(fact, "eventId"), uuid(fact, "batchId"), uuid(fact, "snapshotId"),
                number(fact, "sourceVersionOrdinal"), number(fact, "lineageRevision"),
                stage, text(fact, "factDigest"), text(fact, "watermark"),
                instant(fact, "observedAt"));
    }

    private Map<String, Object> decision(
            UUID jobId, RecoveryObservationDecision value) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("decisionId", jobId.toString());
        out.put("status", value.status().name().toLowerCase().replace('_', '-'));
        out.put("reasonCode", value.reason().name());
        out.put("consecutivePassedBatches", value.consecutivePassedBatches());
        out.put("requiredPassedBatches", value.requiredConsecutivePassedBatches());
        out.put("observedDurationMicros", value.observedDuration().toNanos() / 1_000);
        out.put("requiredDurationMicros", value.requiredDuration().toNanos() / 1_000);
        out.put("finalWatermark", value.finalWatermark());
        out.put("traceId", digest(jobId.toString()).substring(0, 32));
        out.put("decisionDigest", "sha256:" + digest(
                jobId + "\n" + value.status() + "\n" + value.reason() + "\n"
                        + value.consecutivePassedBatches() + "\n"
                        + value.observedDuration().toNanos() + "\n"
                        + Objects.toString(value.finalWatermark(), "")));
        return out;
    }

    private JsonNode callJson(String sql, Object... args) {
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
            return json.writeValueAsString(value);
        } catch (JacksonException malformed) {
            throw invalid();
        }
    }

    private static RecoveryObservationSourceClass sourceClass(String value) {
        return switch (value) {
            case "streaming" -> RecoveryObservationSourceClass.STREAMING;
            case "daily-batch" -> RecoveryObservationSourceClass.DAILY_BATCH;
            default -> throw invalid();
        };
    }

    private static String text(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid();
        return value.asText();
    }

    private static long number(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isIntegralNumber()) throw invalid();
        return value.asLong();
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

    private static Instant instant(JsonNode root, String name) {
        try {
            Instant value = Instant.parse(text(root, name));
            if (value.getNano() % 1_000 != 0) throw invalid();
            return value;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("INGESTION_QUALITY_PERSISTED_EVIDENCE_INVALID");
    }
}
