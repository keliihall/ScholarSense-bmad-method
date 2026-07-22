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
        int httpPort,
        boolean identityEnabled,
        boolean auditLedgerEnabled,
        String clockSourceReference,
        String auditIngestionPolicyReference,
        String auditHashProfileReference,
        String auditCollectorReference,
        String auditVerifierReference,
        String auditAlertTransportReference,
        String auditMetricBindingReference) {

    private static final String AUDIT_INGESTION_POLICY = "audit-ingestion-policy-1-0-0";
    private static final String AUDIT_HASH_PROFILE = "audit-ledger-hash-1-0-0";
    private static final String AUDIT_COLLECTOR = "audit-collector-1-0-0";
    private static final String AUDIT_VERIFIER = "audit-verifier-1-0-0";
    private static final String AUDIT_ALERT_TRANSPORT = "audit-alert-structured-log-1-0-0";
    private static final String AUDIT_METRIC_BINDING = "audit-micrometer-1-0-0";

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
        boolean identityEnabled = strictBoolean(
                values.get("SCHOLARSENSE_IDENTITY_ENABLED"), "SCHOLARSENSE_IDENTITY_ENABLED");
        boolean auditLedgerEnabled = strictBoolean(
                values.get("SCHOLARSENSE_AUDIT_LEDGER_ENABLED"), "SCHOLARSENSE_AUDIT_LEDGER_ENABLED");
        if (identityEnabled && role != RuntimeRole.WEB_API) {
            throw new ConfigurationException(
                    "CONFIG_ROLE_CAPABILITY_MISMATCH",
                    "SCHOLARSENSE_IDENTITY_ENABLED",
                    "identity access can only be enabled for web-api");
        }
        if (auditLedgerEnabled && role != RuntimeRole.WORKER) {
            throw new ConfigurationException(
                    "CONFIG_ROLE_CAPABILITY_MISMATCH",
                    "SCHOLARSENSE_AUDIT_LEDGER_ENABLED",
                    "audit ledger collection can only be enabled for worker");
        }
        String clockSourceReference = null;
        String clockSourceValue = values.get("SCHOLARSENSE_CLOCK_SOURCE_REF");
        if (identityEnabled || auditLedgerEnabled) {
            clockSourceReference = environmentReference(
                    required(values, "SCHOLARSENSE_CLOCK_SOURCE_REF"),
                    "SCHOLARSENSE_CLOCK_SOURCE_REF", "config", environment);
        } else if (clockSourceValue != null && !clockSourceValue.isBlank()) {
            clockSourceReference = environmentReference(
                    clockSourceValue.trim(), "SCHOLARSENSE_CLOCK_SOURCE_REF", "config", environment);
        }
        String auditIngestionPolicyReference = controlledAuditReference(
                values, "SCHOLARSENSE_AUDIT_INGESTION_POLICY_REF", environment,
                auditLedgerEnabled, AUDIT_INGESTION_POLICY);
        String auditHashProfileReference = controlledAuditReference(
                values, "SCHOLARSENSE_AUDIT_HASH_PROFILE_REF", environment,
                auditLedgerEnabled, AUDIT_HASH_PROFILE);
        String auditCollectorReference = controlledAuditReference(
                values, "SCHOLARSENSE_AUDIT_COLLECTOR_REF", environment,
                auditLedgerEnabled, AUDIT_COLLECTOR);
        String auditVerifierReference = controlledAuditReference(
                values, "SCHOLARSENSE_AUDIT_VERIFIER_REF", environment,
                auditLedgerEnabled, AUDIT_VERIFIER);
        String auditAlertTransportReference = controlledAuditReference(
                values, "SCHOLARSENSE_AUDIT_ALERT_TRANSPORT_REF", environment,
                auditLedgerEnabled, AUDIT_ALERT_TRANSPORT);
        String auditMetricBindingReference = controlledAuditReference(
                values, "SCHOLARSENSE_AUDIT_METRIC_BINDING_REF", environment,
                auditLedgerEnabled, AUDIT_METRIC_BINDING);
        return new RuntimeConfiguration(
                environment,
                role,
                accountReference,
                databaseReference,
                secretReference,
                storageNamespace,
                externalBaseUri,
                httpPort,
                identityEnabled,
                auditLedgerEnabled,
                clockSourceReference,
                auditIngestionPolicyReference,
                auditHashProfileReference,
                auditCollectorReference,
                auditVerifierReference,
                auditAlertTransportReference,
                auditMetricBindingReference);
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

    private static String controlledAuditReference(
            Map<String, String> values,
            String field,
            RuntimeEnvironment environment,
            boolean required,
            String expectedResource) {
        String raw = values.get(field);
        if ((raw == null || raw.isBlank()) && !required) {
            return null;
        }
        String reference = environmentReference(
                RuntimeConfiguration.required(values, field), field, "config", environment);
        URI uri = parseUri(reference, field);
        if (!("/" + expectedResource).equals(uri.getPath())) {
            throw new ConfigurationException(
                    "CONFIG_STALE_REFERENCE", field, "must reference the current controlled version");
        }
        return reference;
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

    private static boolean strictBoolean(String value, String field) {
        if (value == null || value.isBlank() || "false".equals(value)) {
            return false;
        }
        if ("true".equals(value)) {
            return true;
        }
        throw new ConfigurationException(
                "CONFIG_INVALID", field, "must be true or false");
    }
}
