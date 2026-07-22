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

/** Resolved values for the currently controlled, versioned audit runtime references. */
public record AuditRuntimeProfile(
        String ingestionPolicyVersion,
        String hashProfileVersion,
        int collectorBatchSize,
        Duration collectorClaimLease,
        Duration collectorInitialDelay,
        Duration collectorInterval,
        int verifierBatchSize,
        Duration verifierInitialDelay,
        Duration verifierInterval,
        int alertBatchSize,
        Duration alertClaimLease,
        Duration alertInitialDelay,
        Duration alertInterval,
        long measurementStaleAfterSeconds,
        long degradedAgeSeconds,
        long degradedCount,
        long blockedAgeSeconds,
        long blockedCount,
        int recoveryHealthyObservations) {

    public static AuditRuntimeProfile from(RuntimeConfiguration runtime) {
        return from(runtime, AuditRuntimeProfile.class::getResourceAsStream);
    }

    static AuditRuntimeProfile from(
            RuntimeConfiguration runtime, Function<String, InputStream> resources) {
        if (runtime == null
                || !runtime.auditLedgerEnabled()
                || runtime.auditIngestionPolicyReference() == null
                || runtime.auditHashProfileReference() == null
                || runtime.auditCollectorReference() == null
                || runtime.auditVerifierReference() == null
                || runtime.auditAlertTransportReference() == null
                || runtime.auditMetricBindingReference() == null) {
            throw new IllegalStateException("AUDIT_RUNTIME_BINDINGS_REQUIRED");
        }
        Config ingestion = load(
                runtime.auditIngestionPolicyReference(), resources,
                Set.of("schemaVersion", "measurementStaleAfterSeconds", "degradedAgeSeconds",
                        "degradedCount", "blockedAgeSeconds", "blockedCount",
                        "recoveryHealthyObservations"));
        Config hash = load(
                runtime.auditHashProfileReference(), resources, Set.of("schemaVersion"));
        Config collector = load(
                runtime.auditCollectorReference(), resources,
                Set.of("schemaVersion", "batchSize", "claimLeaseSeconds",
                        "initialDelaySeconds", "intervalSeconds"));
        Config verifier = load(
                runtime.auditVerifierReference(), resources,
                Set.of("schemaVersion", "batchSize", "initialDelaySeconds", "intervalSeconds"));
        Config alert = load(
                runtime.auditAlertTransportReference(), resources,
                Set.of("schemaVersion", "transport", "batchSize", "claimLeaseSeconds",
                        "initialDelaySeconds", "intervalSeconds"));
        Config metric = load(
                runtime.auditMetricBindingReference(), resources,
                Set.of("schemaVersion", "backend"));
        ingestion.require("schemaVersion", "AUDIT-INGESTION-POLICY-1.0.0");
        hash.require("schemaVersion", "AUDIT-LEDGER-HASH-1.0.0");
        collector.require("schemaVersion", "AUDIT-COLLECTOR-1.0.0");
        verifier.require("schemaVersion", "AUDIT-VERIFIER-1.0.0");
        alert.require("schemaVersion", "AUDIT-ALERT-TRANSPORT-1.0.0");
        alert.require("transport", "structured-log");
        metric.require("schemaVersion", "AUDIT-METRIC-BINDING-1.0.0");
        metric.require("backend", "micrometer");
        return new AuditRuntimeProfile(
                ingestion.value("schemaVersion"),
                hash.value("schemaVersion"),
                collector.positiveInt("batchSize"),
                collector.positiveDuration("claimLeaseSeconds"),
                collector.positiveDuration("initialDelaySeconds"),
                collector.positiveDuration("intervalSeconds"),
                verifier.positiveInt("batchSize"),
                verifier.positiveDuration("initialDelaySeconds"),
                verifier.positiveDuration("intervalSeconds"),
                alert.positiveInt("batchSize"),
                alert.positiveDuration("claimLeaseSeconds"),
                alert.positiveDuration("initialDelaySeconds"),
                alert.positiveDuration("intervalSeconds"),
                ingestion.positiveLong("measurementStaleAfterSeconds"),
                ingestion.positiveLong("degradedAgeSeconds"),
                ingestion.positiveLong("degradedCount"),
                ingestion.positiveLong("blockedAgeSeconds"),
                ingestion.positiveLong("blockedCount"),
                ingestion.positiveInt("recoveryHealthyObservations"));
    }

    private static Config load(
            String reference, Function<String, InputStream> resources, Set<String> expectedKeys) {
        URI uri = URI.create(reference);
        String resourcePath = "/audit-runtime" + uri.getPath() + ".properties";
        InputStream stream = resources.apply(resourcePath);
        if (stream == null) {
            throw new IllegalStateException("AUDIT_RUNTIME_RESOURCE_MISSING");
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
                    throw new IllegalStateException("AUDIT_RUNTIME_RESOURCE_INVALID");
                }
                String key = value.substring(0, separator).trim();
                String configured = value.substring(separator + 1).trim();
                if (!key.matches("[a-z][A-Za-z0-9]{2,63}")
                        || configured.isEmpty()
                        || values.putIfAbsent(key, configured) != null) {
                    throw new IllegalStateException("AUDIT_RUNTIME_RESOURCE_INVALID");
                }
            }
        } catch (IOException unavailable) {
            throw new IllegalStateException("AUDIT_RUNTIME_RESOURCE_UNAVAILABLE", unavailable);
        }
        if (!values.keySet().equals(expectedKeys)) {
            throw new IllegalStateException("AUDIT_RUNTIME_RESOURCE_INVALID");
        }
        return new Config(Map.copyOf(values));
    }

    private record Config(Map<String, String> values) {
        String value(String key) {
            return values.get(key);
        }

        void require(String key, String expected) {
            if (!expected.equals(value(key))) {
                throw new IllegalStateException("AUDIT_RUNTIME_RESOURCE_STALE");
            }
        }

        int positiveInt(String key) {
            long parsed = positiveLong(key);
            if (parsed > Integer.MAX_VALUE) {
                throw new IllegalStateException("AUDIT_RUNTIME_RESOURCE_INVALID");
            }
            return (int) parsed;
        }

        long positiveLong(String key) {
            try {
                long parsed = Long.parseLong(value(key));
                if (parsed < 1) {
                    throw new NumberFormatException("not positive");
                }
                return parsed;
            } catch (NumberFormatException invalid) {
                throw new IllegalStateException("AUDIT_RUNTIME_RESOURCE_INVALID", invalid);
            }
        }

        Duration positiveDuration(String key) {
            return Duration.ofSeconds(positiveLong(key));
        }
    }
}
