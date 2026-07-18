package cn.edu.suda.scholarsense.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.edu.suda.scholarsense.ScholarSenseApplication;
import java.util.HashMap;
import java.util.Map;
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
    void workerUsesTheSameArtifactWithoutStartingAnHttpServer() {
        try (ConfigurableApplicationContext context = ScholarSenseApplication.run(
                RuntimeConfigurationTest.validEnvironment("worker"))) {
            assertFalse(context instanceof WebServerApplicationContext);
        }
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
