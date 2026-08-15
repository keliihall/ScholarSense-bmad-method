package cn.edu.suda.scholarsense.shared.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Contract-backed allowlist that prevents PII and high-cardinality metric labels. */
public final class SafeObservationAttributes {
    private static final Map<String, String> SPAN_NAMES = Map.of(
            "service", "service.name",
            "module", "scholarsense.module",
            "role", "scholarsense.role",
            "operation", "scholarsense.operation",
            "outcome", "scholarsense.outcome",
            "aggregateVersion", "scholarsense.aggregate.version");
    private static final Map<String, Set<String>> LOW_VALUES = Map.of(
            "service", Set.of("scholarsense"),
            "module", Set.of(
                    "shared", "web-api", "worker", "identity-access", "audit-operations",
                    "ingestion-quality", "subject-registry", "public-integration"),
            "role", Set.of(
                    "web-api", "worker", "identity-org", "responsibility",
                    "incremental", "full-chain"),
            "operation", Set.of(
                    "http.server", "job.attempt", "batch.evaluate", "job.finalize",
                    "outbox.publish", "event.consume", "http.client", "telemetry.export",
                    "scholarsense.operation", "audit.ingestion.outcome",
                    "audit.availability.state", "audit.alert.delivery", "audit.verifier.result",
                    "identity-sync-applied-total", "identity-sync-slo-probe-total",
                    "identity-sync-job-total", "responsibility-sync-slo-probe-total",
                    "responsibility-sync-applied-total", "responsibility-sync-job-total",
                    "responsibility-sync-slo-retry-total"),
            "outcome", Set.of(
                    "success", "denied", "conflict", "duplicate", "gap", "poison",
                    "timeout", "unavailable", "failure", "appended", "collision", "rejected",
                    "confirmed", "retried", "fenced", "healthy", "unhealthy", "degraded",
                    "blocked", "applied", "compensation-failed", "compensation-required",
                    "met", "missed", "no-change", "queued", "running", "succeeded",
                    "failed", "cancelled"),
            "code", Set.of(
                    "ok", "invalid", "unauthorized", "forbidden", "conflict", "duplicate",
                    "old", "gap", "poison", "timeout", "unavailable", "error", "warning",
                    "critical", "responsibility-authority-1.0.0",
                    "responsibility-authority-2.0.0"),
            "dependency", Set.of(
                    "identity-authority", "responsibility-authority", "quality-worker",
                    "otlp-traces", "otlp-metrics", "src-p0-responsibility-001"),
            "error", Set.of("none", "error", "export"));
    private static final Set<String> HIGH_KEYS = Set.of("aggregateVersion");
    private static final Set<String> FORBIDDEN_FRAGMENTS = Set.of(
            "张三", "20260001", "证据正文-不可导出", "api-key-secret",
            "Bearer secret-token", "session=cookie-secret", "-----BEGIN CERTIFICATE-----");

    private final Map<String, String> low = new LinkedHashMap<>();
    private final Map<String, String> high = new LinkedHashMap<>();

    private SafeObservationAttributes() {}

    public static SafeObservationAttributes create() {
        return new SafeObservationAttributes();
    }

    public SafeObservationAttributes low(String name, String value) {
        validateLow(name, value);
        low.put(name, value);
        return this;
    }

    public SafeObservationAttributes high(String name, String value) {
        validateShape(HIGH_KEYS, name, value, "high-cardinality");
        if ("aggregateVersion".equals(name) && !value.matches("0|[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException(name + " contains an unapproved high-cardinality value");
        }
        high.put(name, value);
        return this;
    }

    public Map<String, String> metricLabels() {
        return Map.copyOf(low);
    }

    public Map<String, String> spanAttributes() {
        Map<String, String> combined = new LinkedHashMap<>();
        low.forEach((name, value) -> {
            String spanName = SPAN_NAMES.get(name);
            if (spanName != null) combined.put(spanName, value);
        });
        high.forEach((name, value) -> combined.put(SPAN_NAMES.get(name), value));
        return Map.copyOf(combined);
    }

    private static void validateLow(String name, String value) {
        Set<String> values = LOW_VALUES.get(name);
        if (values == null) {
            throw new IllegalArgumentException(name + " is not an approved low-cardinality field");
        }
        validateShape(LOW_VALUES.keySet(), name, value, "low-cardinality");
        if (!values.contains(value)) {
            throw new IllegalArgumentException(name + " contains an unapproved low-cardinality value");
        }
    }

    private static void validateShape(
            Set<String> allowlist, String name, String value, String classification) {
        if (!allowlist.contains(name)) {
            throw new IllegalArgumentException(name + " is not an approved " + classification + " field");
        }
        if (value == null || value.isBlank() || value.length() > 64
                || !value.matches("[a-z0-9][a-z0-9.-]*")) {
            throw new IllegalArgumentException(name + " contains an unbounded or sensitive value");
        }
        if (FORBIDDEN_FRAGMENTS.stream().anyMatch(value::contains)) {
            throw new IllegalArgumentException(name + " contains a denied value");
        }
    }
}
