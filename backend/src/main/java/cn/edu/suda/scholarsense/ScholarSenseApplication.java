package cn.edu.suda.scholarsense;

import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.runtime.RuntimeRole;
import cn.edu.suda.scholarsense.runtime.AuditRuntimeProfile;
import cn.edu.suda.scholarsense.runtime.IdentityAuthorityRuntimeProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

@SpringBootApplication
public class ScholarSenseApplication {

    public static void main(String[] args) {
        run(System.getenv(), args);
    }

    public static ConfigurableApplicationContext run(Map<String, String> environment, String... args) {
        RuntimeConfiguration runtime = RuntimeConfiguration.from(environment);
        SpringApplication application = new SpringApplication(ScholarSenseApplication.class);
        application.setWebApplicationType(
                runtime.role() == RuntimeRole.WEB_API ? WebApplicationType.SERVLET : WebApplicationType.NONE);
        application.setAddCommandLineProperties(false);
        StandardEnvironment springEnvironment = new StandardEnvironment();
        springEnvironment.getPropertySources().addFirst(
                new MapPropertySource("scholarsenseControlledRuntime", controlledProperties(runtime)));
        application.setEnvironment(springEnvironment);
        application.addInitializers(context ->
                context.getBeanFactory().registerSingleton("runtimeConfiguration", runtime));
        return application.run(args);
    }

    static Map<String, Object> controlledProperties(RuntimeConfiguration runtime) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.application.name", "scholarsense");
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.main.web-application-type",
                runtime.role() == RuntimeRole.WEB_API ? "servlet" : "none");
        properties.put("scholarsense.runtime.environment", runtime.environment().wireName());
        properties.put("scholarsense.runtime.role", runtime.role().wireName());
        properties.put("scholarsense.identity.enabled", runtime.identityEnabled());
        properties.put("scholarsense.identity-sync.enabled", runtime.identitySyncEnabled());
        properties.put("scholarsense.audit-ledger.enabled", runtime.auditLedgerEnabled());
        properties.put(
                "logging.structured.format.console",
                "cn.edu.suda.scholarsense.shared.observability.ScholarSenseStructuredLogFormatter");
        properties.put("logging.charset.console", "UTF-8");
        properties.put("spring.jackson.time-zone", "UTC");
        properties.put(
                "management.tracing.export.otlp.enabled",
                runtime.observability().exportEnabled());
        properties.put(
                "management.otlp.metrics.export.enabled",
                runtime.observability().exportEnabled());
        properties.put(
                "management.opentelemetry.tracing.sampler",
                runtime.observability().sampler());
        properties.put(
                "management.tracing.sampling.probability",
                runtime.observability().samplingProbability());
        properties.put(
                "management.opentelemetry.tracing.export.max-queue-size",
                runtime.observability().maxQueueSize());
        properties.put(
                "management.opentelemetry.tracing.export.max-batch-size",
                Math.min(512, runtime.observability().maxQueueSize()));
        properties.put("management.opentelemetry.tracing.export.include-unsampled", false);
        properties.put("management.opentelemetry.tracing.limits.max-attributes", 32);
        properties.put("management.opentelemetry.tracing.limits.max-attribute-value-length", 256);
        properties.put(
                "management.opentelemetry.resource-attributes[service.name]",
                runtime.observability().serviceName());
        properties.put(
                "management.opentelemetry.resource-attributes[scholarsense.module]",
                runtime.observability().moduleName());
        properties.put("management.tracing.baggage.remote-fields", "");
        properties.put("management.tracing.baggage.correlation.fields", "");
        properties.put("spring.task.execution.pool.core-size", 2);
        properties.put("spring.task.execution.pool.max-size", 8);
        properties.put("spring.task.execution.pool.queue-capacity", 256);
        properties.put("spring.task.execution.thread-name-prefix", "scholarsense-task-");
        if (runtime.observability().exportEnabled()) {
            properties.put(
                    "management.opentelemetry.tracing.export.otlp.endpoint",
                    runtime.observability().otlpEndpoint().toString());
            properties.put("management.opentelemetry.tracing.export.otlp.transport", "http");
            properties.put(
                    "management.opentelemetry.tracing.export.otlp.connect-timeout",
                    runtime.observability().exportTimeout().toMillis() + "ms");
            properties.put(
                    "management.opentelemetry.tracing.export.otlp.timeout",
                    runtime.observability().exportTimeout().toMillis() + "ms");
            properties.put(
                    "management.opentelemetry.tracing.export.timeout",
                    runtime.observability().exportTimeout().toMillis() + "ms");
            properties.put(
                    "management.otlp.metrics.export.url",
                    runtime.observability().otlpMetricsEndpoint().toString());
            properties.put(
                    "management.otlp.metrics.export.connect-timeout",
                    runtime.observability().exportTimeout().toMillis() + "ms");
            properties.put(
                    "management.otlp.metrics.export.read-timeout",
                    runtime.observability().exportTimeout().toMillis() + "ms");
        }
        if (runtime.identityAuthorityProfileReference() != null) {
            properties.put(
                    "scholarsense.identity-sync.profile-ref",
                    runtime.identityAuthorityProfileReference());
        }
        if (runtime.identitySyncEnabled()) {
            IdentityAuthorityRuntimeProfile identityAuthority =
                    IdentityAuthorityRuntimeProfile.from(runtime);
            properties.put(
                    "scholarsense.identity-sync.poll-interval",
                    identityAuthority.pollInterval().toMillis());
        }
        if (runtime.auditLedgerEnabled()) {
            AuditRuntimeProfile audit = AuditRuntimeProfile.from(runtime);
            properties.put("scholarsense.audit.collector.initial-delay", audit.collectorInitialDelay());
            properties.put("scholarsense.audit.collector.interval", audit.collectorInterval());
            properties.put("scholarsense.audit.verifier.initial-delay", audit.verifierInitialDelay());
            properties.put("scholarsense.audit.verifier.interval", audit.verifierInterval());
            properties.put("scholarsense.audit.alert.initial-delay", audit.alertInitialDelay());
            properties.put("scholarsense.audit.alert.interval", audit.alertInterval());
        }
        if (runtime.clockSourceReference() != null) {
            properties.put("scholarsense.identity.clock-source-ref", runtime.clockSourceReference());
        }
        properties.put("scholarsense.identity.application-origin",
                runtime.externalBaseUri().getScheme() + "://" + runtime.externalBaseUri().getHost());
        properties.put("server.servlet.session.cookie.name", "__Host-ScholarSense");
        properties.put("server.servlet.session.cookie.path", "/");
        properties.put("server.servlet.session.cookie.secure", "true");
        properties.put("server.servlet.session.cookie.http-only", "true");
        properties.put("server.servlet.session.cookie.same-site", "lax");
        properties.put("server.servlet.session.timeout", "15m");
        properties.put("spring.session.jdbc.initialize-schema", "never");
        properties.put("spring.session.jdbc.table-name", "identity_access.ia_spring_session");
        if (!runtime.identityEnabled()
                && !runtime.identitySyncEnabled()
                && !runtime.auditLedgerEnabled()) {
            properties.put("spring.autoconfigure.exclude", String.join(",",
                    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                    "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
                    "org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration"));
        }
        properties.put("management.endpoints.web.exposure.include", "health");
        properties.put("management.endpoint.health.probes.enabled", "true");
        properties.put("management.endpoint.health.probes.add-additional-paths", "true");
        properties.put("management.endpoint.health.show-details", "never");
        properties.put("server.port", runtime.httpPort());
        return properties;
    }
}
