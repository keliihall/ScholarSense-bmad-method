package cn.edu.suda.scholarsense;

import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.runtime.RuntimeRole;
import cn.edu.suda.scholarsense.runtime.AuditRuntimeProfile;
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

    private static Map<String, Object> controlledProperties(RuntimeConfiguration runtime) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.application.name", "scholarsense");
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.main.web-application-type",
                runtime.role() == RuntimeRole.WEB_API ? "servlet" : "none");
        properties.put("scholarsense.runtime.environment", runtime.environment().wireName());
        properties.put("scholarsense.runtime.role", runtime.role().wireName());
        properties.put("scholarsense.identity.enabled", runtime.identityEnabled());
        properties.put("scholarsense.audit-ledger.enabled", runtime.auditLedgerEnabled());
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
        if (!runtime.identityEnabled() && !runtime.auditLedgerEnabled()) {
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
