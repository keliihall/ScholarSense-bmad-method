package cn.edu.suda.scholarsense;

import cn.edu.suda.scholarsense.runtime.RuntimeConfiguration;
import cn.edu.suda.scholarsense.runtime.RuntimeRole;
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
        properties.put("management.endpoints.web.exposure.include", "health");
        properties.put("management.endpoint.health.probes.enabled", "true");
        properties.put("management.endpoint.health.probes.add-additional-paths", "true");
        properties.put("management.endpoint.health.show-details", "never");
        properties.put("server.port", runtime.httpPort());
        return properties;
    }
}
