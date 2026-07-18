package cn.edu.suda.scholarsense.runtime;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

public record RuntimeConfiguration(
        RuntimeEnvironment environment,
        RuntimeRole role,
        String accountReference,
        String databaseReference,
        String secretReference,
        String storageNamespace,
        URI externalBaseUri,
        int httpPort) {

    public static RuntimeConfiguration from(Map<String, String> values) {
        RuntimeEnvironment environment = RuntimeEnvironment.parse(required(values, "SCHOLARSENSE_ENV"));
        RuntimeRole role = RuntimeRole.parse(required(values, "SCHOLARSENSE_ROLE"));
        String accountReference = environmentReference(
                required(values, "SCHOLARSENSE_ACCOUNT_REF"), "SCHOLARSENSE_ACCOUNT_REF", "account", environment);
        String databaseReference = environmentReference(
                required(values, "SCHOLARSENSE_DATABASE_REF"), "SCHOLARSENSE_DATABASE_REF", "database", environment);
        String secretReference = environmentReference(
                required(values, "SCHOLARSENSE_SECRET_REF"), "SCHOLARSENSE_SECRET_REF", "secret", environment);
        String storageNamespace = storageNamespace(
                required(values, "SCHOLARSENSE_STORAGE_NAMESPACE"), environment);
        URI externalBaseUri = externalUri(
                required(values, "SCHOLARSENSE_EXTERNAL_BASE_URI"), environment);
        int httpPort = httpPort(values.get("SCHOLARSENSE_HTTP_PORT"));
        return new RuntimeConfiguration(
                environment,
                role,
                accountReference,
                databaseReference,
                secretReference,
                storageNamespace,
                externalBaseUri,
                httpPort);
    }

    private static String required(Map<String, String> values, String field) {
        String value = values.get(field);
        if (value == null || value.isBlank()) {
            throw new ConfigurationException("CONFIG_REQUIRED", field, "is required");
        }
        return value.trim();
    }

    private static String environmentReference(
            String value, String field, String scheme, RuntimeEnvironment environment) {
        URI uri = parseUri(value, field);
        if (!scheme.equals(uri.getScheme()) || !environment.wireName().equals(uri.getHost())) {
            throw new ConfigurationException(
                    "CONFIG_CROSS_ENVIRONMENT", field, "must reference the selected environment");
        }
        if (uri.getPort() != -1
                || uri.getQuery() != null
                || uri.getRawPath() == null
                || !uri.getRawPath().matches("/[a-z0-9-]+")) {
            throw new ConfigurationException(
                    "CONFIG_INVALID", field, "must identify one environment-scoped resource");
        }
        return value;
    }

    private static String storageNamespace(String value, RuntimeEnvironment environment) {
        if (!value.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                || !value.endsWith("-" + environment.wireName())) {
            throw new ConfigurationException(
                    "CONFIG_CROSS_ENVIRONMENT",
                    "SCHOLARSENSE_STORAGE_NAMESPACE",
                    "must be kebab-case and end with the selected environment");
        }
        return value;
    }

    private static URI externalUri(String value, RuntimeEnvironment environment) {
        URI uri = parseUri(value, "SCHOLARSENSE_EXTERNAL_BASE_URI");
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme())
                || host == null
                || uri.getPort() != -1
                || !host.matches(environment.wireName() + "(?:\\.[a-z0-9-]+)*\\.invalid")) {
            throw new ConfigurationException(
                    "CONFIG_CROSS_ENVIRONMENT",
                    "SCHOLARSENSE_EXTERNAL_BASE_URI",
                    "must be HTTPS and identify the selected environment");
        }
        if (uri.getQuery() != null && (uri.getPath() == null || uri.getPath().isEmpty())) {
            throw new ConfigurationException(
                    "CONFIG_INVALID",
                    "SCHOLARSENSE_EXTERNAL_BASE_URI",
                    "query parameters require an explicit path");
        }
        return uri;
    }

    private static URI parseUri(String value, String field) {
        try {
            URI uri = new URI(value);
            if (uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new URISyntaxException(value, "userinfo and fragments are forbidden");
            }
            return uri;
        } catch (URISyntaxException error) {
            throw new ConfigurationException("CONFIG_INVALID", field, "must be a safe URI");
        }
    }

    private static int httpPort(String value) {
        if (value == null || value.isBlank()) {
            return 8080;
        }
        try {
            int port = Integer.parseInt(value);
            if (port < 0 || port > 65535) {
                throw new NumberFormatException("out of range");
            }
            return port;
        } catch (NumberFormatException error) {
            throw new ConfigurationException(
                    "CONFIG_INVALID", "SCHOLARSENSE_HTTP_PORT", "must be an integer from 0 to 65535");
        }
    }
}
