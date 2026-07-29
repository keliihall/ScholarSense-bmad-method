package cn.edu.suda.scholarsense.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Resolved non-secret bindings for the pinned identity-authority sandbox profile. */
public record IdentityAuthorityRuntimeProfile(
        String schemaVersion,
        String sourceId,
        String feedId,
        String partitionId,
        String consumerProjection,
        URI endpoint,
        Duration connectTimeout,
        Duration requestTimeout,
        String workloadIdentityReference,
        String signatureKeyReference,
        String inboxEncryptionKeyReference,
        String roleMappingReference,
        String roleMappingVersion,
        String roleMappingDigest,
        Duration pollInterval,
        int retryBudget,
        boolean productionEligible) {
    private static final Set<String> KEYS = Set.of(
            "schemaVersion",
            "sourceId",
            "feedId",
            "partitionId",
            "consumerProjection",
            "endpointTemplate",
            "connectTimeoutSeconds",
            "requestTimeoutSeconds",
            "workloadIdentityResource",
            "signatureKeyResource",
            "inboxEncryptionKeyResource",
            "roleMappingResource",
            "roleMappingVersion",
            "roleMappingDigest",
            "pollIntervalSeconds",
            "retryBudget",
            "productionEligible");

    public static IdentityAuthorityRuntimeProfile from(RuntimeConfiguration runtime) {
        return from(runtime, IdentityAuthorityRuntimeProfile.class::getResourceAsStream);
    }

    static IdentityAuthorityRuntimeProfile from(
            RuntimeConfiguration runtime, Function<String, InputStream> resources) {
        if (runtime == null
                || !runtime.identitySyncEnabled()
                || runtime.identityAuthorityProfileReference() == null) {
            throw new IllegalStateException("IDENTITY_AUTHORITY_PROFILE_BINDING_REQUIRED");
        }
        URI reference = URI.create(runtime.identityAuthorityProfileReference());
        String path = "/identity-authority-runtime" + reference.getPath() + ".properties";
        Map<String, String> values = load(path, resources);
        require(values, "schemaVersion", "IDENTITY-AUTHORITY-PROFILE-1.0.0");
        require(values, "sourceId", "SRC-P0-RESPONSIBILITY-001");
        require(values, "consumerProjection", "identity-org");
        require(values, "roleMappingVersion", "IDENTITY-ROLE-MAPPING-1.0.0");
        if (!values.get("roleMappingDigest").matches("sha256:[0-9a-f]{64}")) {
            throw invalid();
        }
        boolean productionEligible = strictBoolean(values.get("productionEligible"));
        if (runtime.environment() == RuntimeEnvironment.PROD && !productionEligible) {
            throw new IllegalStateException(
                    "IDENTITY_AUTHORITY_PROFILE_NOT_PRODUCTION_ELIGIBLE");
        }
        String environment = runtime.environment().wireName();
        String endpointValue =
                values.get("endpointTemplate").replace("{environment}", environment);
        URI endpoint = URI.create(endpointValue);
        if (!"https".equals(endpoint.getScheme())
                || endpoint.getHost() == null
                || !endpoint.getHost().startsWith(environment + ".")
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null
                || endpoint.getQuery() != null) {
            throw invalid();
        }
        return new IdentityAuthorityRuntimeProfile(
                values.get("schemaVersion"),
                values.get("sourceId"),
                controlledName(values, "feedId"),
                controlledName(values, "partitionId"),
                values.get("consumerProjection"),
                endpoint,
                positiveDuration(values, "connectTimeoutSeconds"),
                positiveDuration(values, "requestTimeoutSeconds"),
                "account://" + environment + "/"
                        + controlledName(values, "workloadIdentityResource"),
                "secret://" + environment + "/"
                        + controlledName(values, "signatureKeyResource"),
                "config://" + environment + "/"
                        + controlledName(values, "inboxEncryptionKeyResource"),
                "config://" + environment + "/"
                        + controlledName(values, "roleMappingResource"),
                values.get("roleMappingVersion"),
                values.get("roleMappingDigest"),
                positiveDuration(values, "pollIntervalSeconds"),
                positiveInt(values, "retryBudget"),
                productionEligible);
    }

    private static Map<String, String> load(
            String path, Function<String, InputStream> resources) {
        InputStream stream = resources.apply(path);
        if (stream == null) {
            throw new IllegalStateException("IDENTITY_AUTHORITY_PROFILE_RESOURCE_MISSING");
        }
        Map<String, String> values = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                int separator = value.indexOf('=');
                if (separator < 1 || separator != value.lastIndexOf('=')) {
                    throw invalid();
                }
                String key = value.substring(0, separator).trim();
                String configured = value.substring(separator + 1).trim();
                if (!key.matches("[a-z][A-Za-z0-9]{2,63}")
                        || configured.isEmpty()
                        || values.putIfAbsent(key, configured) != null) {
                    throw invalid();
                }
            }
        } catch (IOException unavailable) {
            throw new IllegalStateException(
                    "IDENTITY_AUTHORITY_PROFILE_RESOURCE_UNAVAILABLE", unavailable);
        }
        if (!values.keySet().equals(KEYS)) {
            throw invalid();
        }
        return Map.copyOf(values);
    }

    private static String controlledName(Map<String, String> values, String key) {
        String value = values.get(key);
        if (!value.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw invalid();
        }
        return value;
    }

    private static void require(
            Map<String, String> values, String key, String expected) {
        if (!expected.equals(values.get(key))) {
            throw new IllegalStateException("IDENTITY_AUTHORITY_PROFILE_RESOURCE_STALE");
        }
    }

    private static int positiveInt(Map<String, String> values, String key) {
        try {
            int parsed = Integer.parseInt(values.get(key));
            if (parsed < 1) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw invalid();
        }
    }

    private static Duration positiveDuration(
            Map<String, String> values, String key) {
        return Duration.ofSeconds(positiveInt(values, key));
    }

    private static boolean strictBoolean(String value) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw invalid();
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("IDENTITY_AUTHORITY_PROFILE_RESOURCE_INVALID");
    }
}
