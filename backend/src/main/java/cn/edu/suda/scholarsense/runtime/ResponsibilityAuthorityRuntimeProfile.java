package cn.edu.suda.scholarsense.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Resolved non-secret bindings for the pinned responsibility-authority sandbox profile. */
public record ResponsibilityAuthorityRuntimeProfile(
        String profileVersion,
        String sourceId,
        String feedId,
        String partitionId,
        String consumerProjection,
        URI incrementalEndpoint,
        URI snapshotEndpointTemplate,
        Duration requestTimeout,
        int maximumResponseBytes,
        int maximumRecordsPerBatch,
        String workloadIdentityReference,
        String signatureKeyReference,
        String inboxEncryptionKeyReference,
        ZoneId scheduleTimeZone,
        String scheduleCron,
        Duration catchUpWindow,
        int retryBudget,
        boolean productionEligible,
        URI version2IncrementalEndpoint,
        URI version2SnapshotEndpointTemplate,
        String writeContractVersion,
        Instant effectiveAt,
        Duration dualReadWindow,
        boolean cutoverEnabled,
        String cutoverCron,
        String lineageDigestProfile,
        String contractProfileDigest) {
    private static final String PROFILE_VERSION =
            "RESPONSIBILITY-AUTHORITY-PROFILE-2.0.0";
    private static final String VERSION_1 =
            "RESPONSIBILITY-AUTHORITY-1.0.0";
    private static final String VERSION_2 =
            "RESPONSIBILITY-AUTHORITY-2.0.0";
    private static final Instant VERSION_2_EFFECTIVE_AT =
            Instant.parse("2026-07-31T16:00:00Z");
    private static final Duration DUAL_READ_WINDOW =
            Duration.ofDays(14);
    private static final String CUTOVER_CRON =
            "0 */15 * * * *";
    private static final String LINEAGE_DIGEST_PROFILE =
            "RESPONSIBILITY-LINEAGE-DIGEST-1.0.0";
    private static final String CONTRACT_PROFILE_DIGEST =
            "198e82c1b1cc29cb723e67593166afc9372b79f62c93c659962ed24788e96342";
    private static final Set<String> KEYS = Set.of(
            "profileVersion",
            "sourceId",
            "feedId",
            "partitionId",
            "consumerProjection",
            "version1IncrementalEndpointTemplate",
            "version1SnapshotEndpointTemplate",
            "version2IncrementalEndpointTemplate",
            "version2SnapshotEndpointTemplate",
            "maximumResponseBytes",
            "maximumRecordsPerBatch",
            "workloadIdentityResource",
            "signatureKeyResource",
            "inboxEncryptionKeyResource",
            "scheduleTimeZone",
            "scheduleCron",
            "catchUpWindowHours",
            "retryBudget",
            "writeContractVersion",
            "effectiveAt",
            "dualReadWindowHours",
            "cutoverEnabled",
            "cutoverCron",
            "lineageDigestProfile",
            "contractProfileDigest",
            "productionEligible");

    public ResponsibilityAuthorityRuntimeProfile {
        if (!PROFILE_VERSION.equals(profileVersion)
                || !"SRC-P0-RESPONSIBILITY-001".equals(sourceId)
                || feedId == null
                || !feedId.matches("[a-z][a-z0-9-]{2,63}")
                || partitionId == null
                || !partitionId.matches("[a-z0-9][a-z0-9-]{0,63}")
                || !"responsibility".equals(consumerProjection)
                || incrementalEndpoint == null
                || !java.util.Set.of("http", "https").contains(
                        incrementalEndpoint.getScheme())
                || incrementalEndpoint.getHost() == null
                || incrementalEndpoint.getUserInfo() != null
                || incrementalEndpoint.getFragment() != null
                || incrementalEndpoint.getQuery() != null
                || snapshotEndpointTemplate == null
                || !Set.of("http", "https").contains(
                        snapshotEndpointTemplate.getScheme())
                || snapshotEndpointTemplate.getHost() == null
                || !snapshotEndpointTemplate.getPath()
                        .endsWith("/__business_date__")
                || requestTimeout == null
                || requestTimeout.isZero()
                || requestTimeout.isNegative()
                || maximumResponseBytes < 1
                || maximumResponseBytes > 4 * 1024 * 1024
                || maximumRecordsPerBatch < 1
                || maximumRecordsPerBatch > 1_000
                || blank(workloadIdentityReference)
                || blank(signatureKeyReference)
                || blank(inboxEncryptionKeyReference)
                || !ZoneId.of("Asia/Shanghai").equals(scheduleTimeZone)
                || !"0 0 6 * * *".equals(scheduleCron)
                || !Duration.ofHours(24).equals(catchUpWindow)
                || retryBudget < 1
                || !validEndpoint(
                        version2IncrementalEndpoint, false)
                || !validEndpoint(
                        version2SnapshotEndpointTemplate, true)
                || !VERSION_2.equals(writeContractVersion)
                || !VERSION_2_EFFECTIVE_AT.equals(effectiveAt)
                || !DUAL_READ_WINDOW.equals(dualReadWindow)
                || !cutoverEnabled
                || !CUTOVER_CRON.equals(cutoverCron)
                || !LINEAGE_DIGEST_PROFILE.equals(
                        lineageDigestProfile)
                || !CONTRACT_PROFILE_DIGEST.equals(
                        contractProfileDigest)) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_AUTHORITY_PROFILE_INVALID");
        }
        Objects.requireNonNull(incrementalEndpoint, "incrementalEndpoint");
    }

    /** Test/adapter compatibility constructor; runtime assembly uses {@link #from}. */
    public ResponsibilityAuthorityRuntimeProfile(
            String sourceId,
            String feedId,
            String partitionId,
            String consumerProjection,
            URI incrementalEndpoint,
            Duration requestTimeout,
            String workloadIdentityReference,
            String signatureKeyReference,
            String inboxEncryptionKeyReference,
            boolean productionEligible) {
        this(
                PROFILE_VERSION,
                sourceId,
                feedId,
                partitionId,
                consumerProjection,
                incrementalEndpoint,
                URI.create(
                        incrementalEndpoint.resolve("./snapshots/").toString()
                                + "__business_date__"),
                requestTimeout,
                4 * 1024 * 1024,
                1_000,
                workloadIdentityReference,
                signatureKeyReference,
                inboxEncryptionKeyReference,
                ZoneId.of("Asia/Shanghai"),
                "0 0 6 * * *",
                Duration.ofHours(24),
                8,
                productionEligible,
                successorEndpoint(incrementalEndpoint),
                snapshotTemplate(successorEndpoint(
                        incrementalEndpoint)),
                VERSION_2,
                VERSION_2_EFFECTIVE_AT,
                DUAL_READ_WINDOW,
                true,
                CUTOVER_CRON,
                LINEAGE_DIGEST_PROFILE,
                CONTRACT_PROFILE_DIGEST);
    }

    public static ResponsibilityAuthorityRuntimeProfile from(
            RuntimeConfiguration runtime) {
        return from(
                runtime,
                ResponsibilityAuthorityRuntimeProfile.class
                        ::getResourceAsStream);
    }

    static ResponsibilityAuthorityRuntimeProfile from(
            RuntimeConfiguration runtime,
            Function<String, InputStream> resources) {
        if (runtime == null
                || !runtime.identitySyncEnabled()
                || runtime.responsibilityAuthorityProfileReference() == null) {
            throw new IllegalStateException(
                    "RESPONSIBILITY_AUTHORITY_PROFILE_BINDING_REQUIRED");
        }
        URI reference =
                URI.create(runtime.responsibilityAuthorityProfileReference());
        String path = "/responsibility-authority-runtime"
                + reference.getPath()
                + ".properties";
        Map<String, String> values = load(path, resources);
        require(
                values,
                "profileVersion",
                PROFILE_VERSION);
        require(values, "sourceId", "SRC-P0-RESPONSIBILITY-001");
        require(values, "consumerProjection", "responsibility");
        boolean productionEligible =
                strictBoolean(values.get("productionEligible"));
        if (runtime.environment() == RuntimeEnvironment.PROD
                && !productionEligible) {
            throw new IllegalStateException(
                    "RESPONSIBILITY_AUTHORITY_PROFILE_NOT_PRODUCTION_ELIGIBLE");
        }
        String environment = runtime.environment().wireName();
        URI incremental = endpoint(
                values.get("version1IncrementalEndpointTemplate"),
                environment,
                false,
                "v1");
        URI snapshot = endpoint(
                values.get("version1SnapshotEndpointTemplate"),
                environment,
                true,
                "v1");
        URI version2Incremental = endpoint(
                values.get("version2IncrementalEndpointTemplate"),
                environment,
                false,
                "v2");
        URI version2Snapshot = endpoint(
                values.get("version2SnapshotEndpointTemplate"),
                environment,
                true,
                "v2");
        return new ResponsibilityAuthorityRuntimeProfile(
                values.get("profileVersion"),
                values.get("sourceId"),
                controlledName(values, "feedId"),
                controlledName(values, "partitionId"),
                values.get("consumerProjection"),
                incremental,
                snapshot,
                Duration.ofSeconds(20),
                positiveInt(values, "maximumResponseBytes"),
                positiveInt(values, "maximumRecordsPerBatch"),
                "account://" + environment + "/"
                        + controlledName(values, "workloadIdentityResource"),
                "secret://" + environment + "/"
                        + controlledName(values, "signatureKeyResource"),
                "config://" + environment + "/"
                        + controlledName(values, "inboxEncryptionKeyResource"),
                ZoneId.of(values.get("scheduleTimeZone")),
                values.get("scheduleCron"),
                Duration.ofHours(positiveInt(values, "catchUpWindowHours")),
                positiveInt(values, "retryBudget"),
                productionEligible,
                version2Incremental,
                version2Snapshot,
                values.get("writeContractVersion"),
                instant(values, "effectiveAt"),
                Duration.ofHours(positiveInt(
                        values, "dualReadWindowHours")),
                strictBoolean(values.get("cutoverEnabled")),
                values.get("cutoverCron"),
                values.get("lineageDigestProfile"),
                values.get("contractProfileDigest"));
    }

    public void requirePublicHttpsEndpoint() {
        for (URI endpoint : List.of(
                incrementalEndpoint,
                snapshotEndpointTemplate,
                version2IncrementalEndpoint,
                version2SnapshotEndpointTemplate)) {
            requirePublicHttpsEndpoint(endpoint);
        }
    }

    private static void requirePublicHttpsEndpoint(URI endpoint) {
        if (!"https".equals(endpoint.getScheme())) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SOURCE_ENDPOINT_UNSAFE");
        }
        String host = endpoint.getHost();
        if (InetAddress.getLoopbackAddress()
                        .getHostName()
                        .equalsIgnoreCase(host)
                || host.matches("127(?:\\.[0-9]{1,3}){3}")
                || host.matches("10(?:\\.[0-9]{1,3}){3}")
                || host.matches("192\\.168(?:\\.[0-9]{1,3}){2}")
                || host.matches("172\\.(?:1[6-9]|2[0-9]|3[01])(?:\\.[0-9]{1,3}){2}")
                || host.equals("::1")) {
            throw new IllegalArgumentException(
                    "RESPONSIBILITY_SOURCE_ENDPOINT_UNSAFE");
        }
    }

    public URI snapshotEndpoint(java.time.LocalDate businessDate) {
        return snapshotEndpoint(VERSION_1, businessDate);
    }

    public URI version1IncrementalEndpoint() {
        return incrementalEndpoint;
    }

    public URI version1SnapshotEndpointTemplate() {
        return snapshotEndpointTemplate;
    }

    public URI incrementalEndpoint(String contractVersion) {
        return switch (contractVersion) {
            case VERSION_1 -> incrementalEndpoint;
            case VERSION_2 -> version2IncrementalEndpoint;
            default -> throw new IllegalArgumentException(
                    "RESPONSIBILITY_SOURCE_CONTRACT_UNAPPROVED");
        };
    }

    public URI snapshotEndpoint(
            String contractVersion,
            java.time.LocalDate businessDate) {
        Objects.requireNonNull(businessDate, "businessDate");
        URI template = switch (contractVersion) {
            case VERSION_1 -> snapshotEndpointTemplate;
            case VERSION_2 -> version2SnapshotEndpointTemplate;
            default -> throw new IllegalArgumentException(
                    "RESPONSIBILITY_SOURCE_CONTRACT_UNAPPROVED");
        };
        return URI.create(template.toString().replace(
                "__business_date__", businessDate.toString()));
    }

    public Instant dualReadWindowEndsAt() {
        return effectiveAt.plus(dualReadWindow);
    }

    private static Map<String, String> load(
            String path, Function<String, InputStream> resources) {
        InputStream stream = resources.apply(path);
        if (stream == null) {
            throw new IllegalStateException(
                    "RESPONSIBILITY_AUTHORITY_PROFILE_RESOURCE_MISSING");
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
                    "RESPONSIBILITY_AUTHORITY_PROFILE_RESOURCE_UNAVAILABLE",
                    unavailable);
        }
        if (!values.keySet().equals(KEYS)) {
            throw invalid();
        }
        return Map.copyOf(values);
    }

    private static URI endpoint(
            String template,
            String environment,
            boolean snapshot,
            String apiVersion) {
        String resolved = template.replace("{environment}", environment);
        if (snapshot) {
            resolved = resolved.replace(
                    "{businessDate}", "__business_date__");
        }
        URI endpoint = URI.create(resolved);
        if (!"https".equals(endpoint.getScheme())
                || endpoint.getHost() == null
                || !endpoint.getHost().startsWith(environment + ".")
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null
                || endpoint.getQuery() != null
                || !endpoint.getPath().contains(
                        "/api/" + apiVersion + "/")
                || (snapshot
                        && !endpoint.getPath()
                                .endsWith("/__business_date__"))) {
            throw invalid();
        }
        return endpoint;
    }

    private static void require(
            Map<String, String> values, String key, String expected) {
        if (!expected.equals(values.get(key))) {
            throw new IllegalStateException(
                    "RESPONSIBILITY_AUTHORITY_PROFILE_RESOURCE_STALE");
        }
    }

    private static String controlledName(
            Map<String, String> values, String key) {
        String value = values.get(key);
        if (!value.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw invalid();
        }
        return value;
    }

    private static int positiveInt(
            Map<String, String> values, String key) {
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

    private static Instant instant(
            Map<String, String> values, String key) {
        try {
            return Instant.parse(values.get(key));
        } catch (RuntimeException invalid) {
            throw invalid();
        }
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
        return new IllegalStateException(
                "RESPONSIBILITY_AUTHORITY_PROFILE_RESOURCE_INVALID");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean validEndpoint(
            URI endpoint, boolean snapshot) {
        return endpoint != null
                && Set.of("http", "https").contains(
                        endpoint.getScheme())
                && endpoint.getHost() != null
                && endpoint.getUserInfo() == null
                && endpoint.getFragment() == null
                && endpoint.getQuery() == null
                && (!snapshot
                        || endpoint.getPath()
                                .endsWith("/__business_date__"));
    }

    private static URI successorEndpoint(URI version1) {
        String value = version1.toString();
        return URI.create(value.contains("/v1/")
                ? value.replaceFirst("/v1/", "/v2/")
                : value);
    }

    private static URI snapshotTemplate(URI incremental) {
        return URI.create(
                incremental.resolve("./snapshots/").toString()
                        + "__business_date__");
    }
}
