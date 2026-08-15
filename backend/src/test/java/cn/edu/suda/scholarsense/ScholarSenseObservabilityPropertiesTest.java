package cn.edu.suda.scholarsense;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.runtime.RuntimeConfigurationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScholarSenseObservabilityPropertiesTest {
    @Test
    void nonProductionDisablesExporterButKeepsBoundedTracingKernel() {
        RuntimeConfiguration runtime = RuntimeConfiguration.from(
                RuntimeConfigurationTest.validEnvironment("test", "worker"));

        Map<String, Object> properties = ScholarSenseApplication.controlledProperties(runtime);

        assertEquals(false, properties.get("management.tracing.export.otlp.enabled"));
        assertEquals("parent-based-trace-id-ratio",
                properties.get("management.opentelemetry.tracing.sampler"));
        assertEquals(1.0d, properties.get("management.tracing.sampling.probability"));
        assertEquals(false, properties.get("management.otlp.metrics.export.enabled"));
        assertEquals(2048,
                properties.get("management.opentelemetry.tracing.export.max-queue-size"));
        assertEquals(
                "cn.edu.suda.scholarsense.shared.observability.ScholarSenseStructuredLogFormatter",
                properties.get("logging.structured.format.console"));
    }

    @Test
    void productionMapsOnlyValidatedOtlpAndResourceConfiguration() {
        RuntimeConfiguration runtime = RuntimeConfiguration.from(
                RuntimeConfigurationTest.validEnvironment("prod", "web-api"));

        Map<String, Object> properties = ScholarSenseApplication.controlledProperties(runtime);

        assertEquals(true, properties.get("management.tracing.export.otlp.enabled"));
        assertEquals("https://otel.prod.scholarsense.suda.edu.cn/v1/traces",
                properties.get("management.opentelemetry.tracing.export.otlp.endpoint"));
        assertEquals("2000ms",
                properties.get("management.opentelemetry.tracing.export.otlp.timeout"));
        assertEquals(true, properties.get("management.otlp.metrics.export.enabled"));
        assertEquals("https://otel.prod.scholarsense.suda.edu.cn/v1/metrics",
                properties.get("management.otlp.metrics.export.url"));
        assertEquals("2000ms",
                properties.get("management.otlp.metrics.export.connect-timeout"));
        assertEquals("2000ms",
                properties.get("management.otlp.metrics.export.read-timeout"));
        assertEquals("scholarsense",
                properties.get("management.opentelemetry.resource-attributes[service.name]"));
        assertEquals("web-api",
                properties.get("management.opentelemetry.resource-attributes[scholarsense.module]"));
    }

    @Test
    void subSecondExporterTimeoutNeverTruncatesToZeroSeconds() {
        java.util.HashMap<String, String> environment = new java.util.HashMap<>(
                RuntimeConfigurationTest.validEnvironment("prod", "worker"));
        environment.put("SCHOLARSENSE_OBSERVABILITY_EXPORT_TIMEOUT_MS", "250");

        Map<String, Object> properties = ScholarSenseApplication.controlledProperties(
                RuntimeConfiguration.from(environment));

        assertEquals("250ms",
                properties.get("management.opentelemetry.tracing.export.otlp.timeout"));
        assertEquals("250ms",
                properties.get("management.otlp.metrics.export.read-timeout"));
    }
}
