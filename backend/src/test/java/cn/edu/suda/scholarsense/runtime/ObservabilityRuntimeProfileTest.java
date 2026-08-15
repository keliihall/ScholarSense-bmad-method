package cn.edu.suda.scholarsense.runtime;

import static cn.edu.suda.scholarsense.runtime.RuntimeConfigurationTest.validEnvironment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObservabilityRuntimeProfileTest {
    @Test
    void testProfileUsesExplicitNonProductionDefaultsWithoutExporterClaim() {
        RuntimeConfiguration runtime = RuntimeConfiguration.from(validEnvironment("test", "worker"));

        assertFalse(runtime.observability().exportEnabled());
        assertEquals("parent-based-trace-id-ratio", runtime.observability().sampler());
        assertEquals(1.0d, runtime.observability().samplingProbability());
        assertEquals(Duration.ofSeconds(2), runtime.observability().exportTimeout());
        assertEquals(2048, runtime.observability().maxQueueSize());
    }

    @Test
    void productionRequiresSafeControlledExporterConfiguration() {
        Map<String, String> missing = new HashMap<>(validEnvironment("prod", "web-api"));
        missing.remove("SCHOLARSENSE_OBSERVABILITY_OTLP_ENDPOINT");

        ConfigurationException error = assertThrows(
                ConfigurationException.class, () -> RuntimeConfiguration.from(missing));
        assertEquals("CONFIG_REQUIRED", error.code());
        assertEquals("SCHOLARSENSE_OBSERVABILITY_OTLP_ENDPOINT", error.field());

        Map<String, String> missingMetrics = new HashMap<>(
                validEnvironment("prod", "web-api"));
        missingMetrics.remove("SCHOLARSENSE_OBSERVABILITY_OTLP_METRICS_ENDPOINT");
        ConfigurationException metricsError = assertThrows(
                ConfigurationException.class,
                () -> RuntimeConfiguration.from(missingMetrics));
        assertEquals("SCHOLARSENSE_OBSERVABILITY_OTLP_METRICS_ENDPOINT",
                metricsError.field());

        RuntimeConfiguration runtime = RuntimeConfiguration.from(
                validEnvironment("prod", "web-api"));
        assertTrue(runtime.observability().exportEnabled());
        assertEquals(
                URI.create("https://otel.prod.scholarsense.suda.edu.cn/v1/traces"),
                runtime.observability().otlpEndpoint());
        assertEquals(
                URI.create("https://otel.prod.scholarsense.suda.edu.cn/v1/metrics"),
                runtime.observability().otlpMetricsEndpoint());
        assertEquals("http/protobuf", runtime.observability().otlpProtocol());
        assertEquals("scholarsense", runtime.observability().serviceName());
        assertEquals("web-api", runtime.observability().moduleName());
    }

    @Test
    void unsafeEndpointSamplingTimeoutQueueAndResourceValuesFailFast() {
        assertInvalid("SCHOLARSENSE_OBSERVABILITY_OTLP_ENDPOINT", "http://localhost:4318/v1/traces");
        assertInvalid("SCHOLARSENSE_OBSERVABILITY_OTLP_ENDPOINT", "https://otel.prod.invalid/v1/traces");
        assertInvalid("SCHOLARSENSE_OBSERVABILITY_OTLP_ENDPOINT", "https://otel.stage.scholarsense.suda.edu.cn/v1/traces");
        assertInvalid("SCHOLARSENSE_OBSERVABILITY_OTLP_METRICS_ENDPOINT", "https://otel.prod.invalid/v1/metrics");
        assertInvalid("SCHOLARSENSE_OBSERVABILITY_SAMPLING_PROBABILITY", "1.1");
        assertInvalid("SCHOLARSENSE_OBSERVABILITY_EXPORT_TIMEOUT_MS", "0");
        assertInvalid("SCHOLARSENSE_OBSERVABILITY_MAX_QUEUE_SIZE", "0");
        assertInvalid("SCHOLARSENSE_OBSERVABILITY_SERVICE_NAME", "student-20260001");
        assertInvalid("SCHOLARSENSE_OBSERVABILITY_MODULE_NAME", "other");
    }

    @Test
    void stageUsesItsOwnFrozenEndpointAndRejectsTheProductionEndpoint() {
        RuntimeConfiguration stage = RuntimeConfiguration.from(
                validEnvironment("stage", "worker"));

        assertEquals(
                URI.create("https://otel.stage.scholarsense.suda.edu.cn/v1/traces"),
                stage.observability().otlpEndpoint());
        Map<String, String> drifted = new HashMap<>(validEnvironment("stage", "worker"));
        drifted.put(
                "SCHOLARSENSE_OBSERVABILITY_OTLP_ENDPOINT",
                "https://otel.prod.scholarsense.suda.edu.cn/v1/traces");
        assertThrows(ConfigurationException.class, () -> RuntimeConfiguration.from(drifted));
    }

    private void assertInvalid(String field, String value) {
        Map<String, String> environment = new HashMap<>(validEnvironment("prod", "worker"));
        environment.put(field, value);
        ConfigurationException error = assertThrows(
                ConfigurationException.class, () -> RuntimeConfiguration.from(environment));
        assertEquals("CONFIG_INVALID", error.code());
        assertEquals(field, error.field());
    }
}
