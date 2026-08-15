package cn.edu.suda.scholarsense.runtime;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Map;

/** Versioned, non-secret OpenTelemetry runtime controls. */
public record ObservabilityRuntimeProfile(
        URI otlpEndpoint,
        URI otlpMetricsEndpoint,
        String otlpProtocol,
        Duration exportTimeout,
        int maxQueueSize,
        String sampler,
        double samplingProbability,
        String serviceName,
        String moduleName) {

    private static final Map<RuntimeEnvironment, URI> APPROVED_TRACE_ENDPOINTS = Map.of(
            RuntimeEnvironment.STAGE,
            URI.create("https://otel.stage.scholarsense.suda.edu.cn/v1/traces"),
            RuntimeEnvironment.PROD,
            URI.create("https://otel.prod.scholarsense.suda.edu.cn/v1/traces"));
    private static final Map<RuntimeEnvironment, URI> APPROVED_METRICS_ENDPOINTS = Map.of(
            RuntimeEnvironment.STAGE,
            URI.create("https://otel.stage.scholarsense.suda.edu.cn/v1/metrics"),
            RuntimeEnvironment.PROD,
            URI.create("https://otel.prod.scholarsense.suda.edu.cn/v1/metrics"));

    private static final String ENDPOINT = "SCHOLARSENSE_OBSERVABILITY_OTLP_ENDPOINT";
    private static final String METRICS_ENDPOINT =
            "SCHOLARSENSE_OBSERVABILITY_OTLP_METRICS_ENDPOINT";
    private static final String PROTOCOL = "SCHOLARSENSE_OBSERVABILITY_OTLP_PROTOCOL";
    private static final String TIMEOUT = "SCHOLARSENSE_OBSERVABILITY_EXPORT_TIMEOUT_MS";
    private static final String QUEUE = "SCHOLARSENSE_OBSERVABILITY_MAX_QUEUE_SIZE";
    private static final String SAMPLING = "SCHOLARSENSE_OBSERVABILITY_SAMPLING_PROBABILITY";
    private static final String SERVICE = "SCHOLARSENSE_OBSERVABILITY_SERVICE_NAME";
    private static final String MODULE = "SCHOLARSENSE_OBSERVABILITY_MODULE_NAME";

    public static ObservabilityRuntimeProfile from(
            Map<String, String> values,
            RuntimeEnvironment environment,
            RuntimeRole role) {
        boolean productionLike = environment == RuntimeEnvironment.STAGE
                || environment == RuntimeEnvironment.PROD;
        URI endpoint = endpoint(
                values.get(ENDPOINT), environment, productionLike,
                ENDPOINT, APPROVED_TRACE_ENDPOINTS, "traces");
        URI metricsEndpoint = endpoint(
                values.get(METRICS_ENDPOINT), environment, productionLike,
                METRICS_ENDPOINT, APPROVED_METRICS_ENDPOINTS, "metrics");
        String protocol = optional(values, PROTOCOL, "http/protobuf");
        if (!"http/protobuf".equals(protocol)) {
            throw invalid(PROTOCOL, "must be http/protobuf");
        }
        int timeoutMillis = boundedInteger(
                values.get(TIMEOUT), TIMEOUT, 2_000, 100, 10_000);
        int maxQueueSize = boundedInteger(
                values.get(QUEUE), QUEUE, 2_048, 128, 8_192);
        double probability = probability(values.get(SAMPLING), environment);
        String serviceName = optional(values, SERVICE, "scholarsense");
        if (!"scholarsense".equals(serviceName)) {
            throw invalid(SERVICE, "must be the controlled service resource");
        }
        String moduleName = optional(values, MODULE, role.wireName());
        if (!role.wireName().equals(moduleName)) {
            throw invalid(MODULE, "must match the selected runtime role");
        }
        return new ObservabilityRuntimeProfile(
                endpoint,
                metricsEndpoint,
                protocol,
                Duration.ofMillis(timeoutMillis),
                maxQueueSize,
                "parent-based-trace-id-ratio",
                probability,
                serviceName,
                moduleName);
    }

    public boolean exportEnabled() {
        return otlpEndpoint != null;
    }

    private static URI endpoint(
            String raw,
            RuntimeEnvironment environment,
            boolean required,
            String field,
            Map<RuntimeEnvironment, URI> approvedEndpoints,
            String signal) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new ConfigurationException("CONFIG_REQUIRED", field, "is required");
            }
            return null;
        }
        try {
            URI uri = new URI(raw.trim());
            URI approved = approvedEndpoints.get(environment);
            if (!uri.equals(approved)) {
                throw invalid(field,
                        "must be the environment-scoped HTTPS OTLP " + signal + " endpoint");
            }
            return uri;
        } catch (URISyntaxException error) {
            throw invalid(field, "must be a safe URI");
        }
    }

    private static double probability(String raw, RuntimeEnvironment environment) {
        if (raw == null || raw.isBlank()) {
            return environment == RuntimeEnvironment.TEST ? 1.0d : 0.1d;
        }
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value) || value < 0d || value > 1d) {
                throw new NumberFormatException("out of range");
            }
            return value;
        } catch (NumberFormatException error) {
            throw invalid(SAMPLING, "must be a finite number from 0 to 1");
        }
    }

    private static int boundedInteger(
            String raw, String field, int fallback, int minimum, int maximum) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < minimum || value > maximum) {
                throw new NumberFormatException("out of range");
            }
            return value;
        } catch (NumberFormatException error) {
            throw invalid(field, "is outside the controlled range");
        }
    }

    private static String optional(Map<String, String> values, String field, String fallback) {
        String value = values.get(field);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static ConfigurationException invalid(String field, String reason) {
        return new ConfigurationException("CONFIG_INVALID", field, reason);
    }
}
