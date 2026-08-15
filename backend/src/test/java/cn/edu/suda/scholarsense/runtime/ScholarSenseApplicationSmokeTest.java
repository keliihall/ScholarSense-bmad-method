package cn.edu.suda.scholarsense.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ScholarSenseApplication;
import cn.edu.suda.scholarsense.shared.observability.ObservationPort;
import cn.edu.suda.scholarsense.shared.observability.SafeObservationAttributes;
import cn.edu.suda.scholarsense.shared.observability.TelemetryExportMonitor;
import cn.edu.suda.scholarsense.shared.observability.W3cTraceContext;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

class ScholarSenseApplicationSmokeTest {

    @Test
    void webApiStartsTheHealthOnlyWebRuntime() {
        Map<String, String> environment = new HashMap<>(RuntimeConfigurationTest.validEnvironment("web-api"));
        environment.put("SCHOLARSENSE_HTTP_PORT", "0");

        try (ConfigurableApplicationContext context = ScholarSenseApplication.run(environment)) {
            assertTrue(context instanceof WebServerApplicationContext);
            assertTrue(((WebServerApplicationContext) context).getWebServer().getPort() > 0);
        }
    }

    @Test
    void trustedHttpIngressUsesOneFrameworkTraceInBothResponseHeaders() throws IOException {
        Map<String, String> environment = new HashMap<>(RuntimeConfigurationTest.validEnvironment("web-api"));
        environment.put("SCHOLARSENSE_HTTP_PORT", "0");
        String traceId = "11111111111111111111111111111111";

        try (ConfigurableApplicationContext context = ScholarSenseApplication.run(environment)) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            String response;
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.getOutputStream().write(("GET /actuator/health HTTP/1.1\r\n"
                        + "Host: api.suda.edu.cn\r\n"
                        + "Connection: close\r\n"
                        + "X-ScholarSense-Proxy-Identity: portal-proxy-test-v1\r\n"
                        + "traceparent: 00-" + traceId + "-2222222222222222-01\r\n"
                        + "baggage: studentId=20260001\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }

            assertTrue(response.startsWith("HTTP/1.1 200"), response);
            assertTrue(response.toLowerCase().contains(
                    "x-scholarsense-trace-id: " + traceId), response);
            String responseTraceparent = response.lines()
                    .filter(line -> line.toLowerCase().startsWith("traceparent:"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(responseTraceparent.matches(
                    "(?i)traceparent: 00-" + traceId + "-[0-9a-f]{16}-01"), responseTraceparent);
            assertFalse(response.contains("20260001"));
        }
    }

    @Test
    void workerUsesTheSameArtifactWithoutStartingAnHttpServer() {
        try (ConfigurableApplicationContext context = ScholarSenseApplication.run(
                RuntimeConfigurationTest.validEnvironment("worker"))) {
            assertFalse(context instanceof WebServerApplicationContext);
        }
    }

    @Test
    void bothRolesStartAndKeepBusinessTruthWithAnUnavailableBoundedCollector() {
        for (String role : new String[] {"web-api", "worker"}) {
            Map<String, String> environment = new HashMap<>(
                    RuntimeConfigurationTest.validEnvironment("prod", role));
            environment.put("SCHOLARSENSE_HTTP_PORT", "0");

            try (ConfigurableApplicationContext context = ScholarSenseApplication.run(environment)) {
                RuntimeConfiguration runtime = context.getBean(RuntimeConfiguration.class);
                assertTrue(runtime.observability().exportEnabled());
                assertEquals(2048, runtime.observability().maxQueueSize());
                ObservationPort observations = context.getBean(ObservationPort.class);
                W3cTraceContext parent = new W3cTraceContext(
                        "11111111111111111111111111111111",
                        "2222222222222222",
                        true);
                String operation = role.equals("web-api") ? "http.server" : "job.attempt";
                AtomicBoolean businessCommitted = new AtomicBoolean();
                try (ObservationPort.ObservationScope ignored = observations.start(
                        operation,
                        role.equals("web-api")
                                ? ObservationPort.ObservationKind.SERVER
                                : ObservationPort.ObservationKind.INTERNAL,
                        SafeObservationAttributes.create()
                                .low("module", role)
                                .low("operation", operation)
                                .low("outcome", "success"),
                        parent)) {
                    businessCommitted.set(true);
                }
                assertTrue(businessCommitted.get());
                context.getBean(OpenTelemetrySdk.class).getSdkTracerProvider()
                        .forceFlush().join(10, TimeUnit.SECONDS);
                TelemetryExportMonitor monitor =
                        context.getBean(TelemetryExportMonitor.class);
                assertTrue(monitor.failureCount() > 0);
                assertEquals("DEGRADED", monitor.health().getStatus().getCode());
            }
        }
    }

    @Test
    void workerCannotLoadTheIdentityLoginSurface() {
        Map<String, String> environment = new HashMap<>(RuntimeConfigurationTest.validEnvironment("worker"));
        environment.put("SCHOLARSENSE_IDENTITY_ENABLED", "true");

        try {
            ScholarSenseApplication.run(environment);
        } catch (ConfigurationException error) {
            assertEquals("CONFIG_ROLE_CAPABILITY_MISMATCH", error.code());
            return;
        }
        throw new AssertionError("worker unexpectedly loaded identity access");
    }

    @Test
    void springArgumentsCannotOverrideControlledRolePortOrActuatorExposure() {
        Map<String, String> environment = new HashMap<>(RuntimeConfigurationTest.validEnvironment("web-api"));
        environment.put("SCHOLARSENSE_HTTP_PORT", "0");

        try (ConfigurableApplicationContext context = ScholarSenseApplication.run(
                environment,
                "--spring.main.web-application-type=none",
                "--server.port=65536",
                "--management.endpoints.web.exposure.include=*")) {
            assertTrue(context instanceof WebServerApplicationContext);
            assertTrue(((WebServerApplicationContext) context).getWebServer().getPort() > 0);
            assertEquals("health", context.getEnvironment()
                    .getProperty("management.endpoints.web.exposure.include"));
        }

        try (ConfigurableApplicationContext context = ScholarSenseApplication.run(
                RuntimeConfigurationTest.validEnvironment("worker"),
                "--spring.main.web-application-type=servlet",
                "--server.port=8080",
                "--management.endpoints.web.exposure.include=*")) {
            assertFalse(context instanceof WebServerApplicationContext);
            assertEquals("health", context.getEnvironment()
                    .getProperty("management.endpoints.web.exposure.include"));
        }
    }
}
