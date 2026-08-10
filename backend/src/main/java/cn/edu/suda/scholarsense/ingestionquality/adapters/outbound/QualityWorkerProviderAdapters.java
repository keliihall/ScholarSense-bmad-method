package cn.edu.suda.scholarsense.ingestionquality.adapters.outbound;

import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchAuthorizationDecision;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchAuthorizationPort;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchAuthorizationRequest;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchWorkloadAuthorizationEvidence;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchWorkloadAuthorizationPort;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchWorkloadAuthorizationRequest;
import cn.edu.suda.scholarsense.ingestionquality.application.DataBatchWorkloadAuthorizationResult;
import cn.edu.suda.scholarsense.ingestionquality.application.QualitySnapshotIdPort;
import cn.edu.suda.scholarsense.shared.time.TimeSynchronizationStatus;
import cn.edu.suda.scholarsense.shared.time.TimeSynchronizationStatusProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bounded mTLS transport adapters for every target-managed quality-worker provider port.
 * Provider failures and malformed evidence are deliberately indistinguishable and fail closed.
 */
public final class QualityWorkerProviderAdapters implements
        DataBatchAuthorizationPort,
        DataBatchWorkloadAuthorizationPort,
        QualitySnapshotIdPort,
        TimeSynchronizationStatusProvider {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final URI dataBatchAuthorization;
    private final URI workloadAuthorization;
    private final URI snapshotIds;
    private final URI trustedTime;
    private final ObjectMapper json;
    private final Exchange exchange;

    public QualityWorkerProviderAdapters(
            URI dataBatchAuthorization,
            URI workloadAuthorization,
            URI snapshotIds,
            URI trustedTime,
            ObjectMapper json,
            HttpClient http) {
        this(dataBatchAuthorization, workloadAuthorization, snapshotIds, trustedTime, json,
                httpExchange(java.util.Objects.requireNonNull(http), json));
        if (http.followRedirects() != HttpClient.Redirect.NEVER) throw unavailable();
    }

    QualityWorkerProviderAdapters(
            URI dataBatchAuthorization,
            URI workloadAuthorization,
            URI snapshotIds,
            URI trustedTime,
            ObjectMapper json,
            Exchange exchange) {
        this.dataBatchAuthorization = endpoint(dataBatchAuthorization);
        this.workloadAuthorization = endpoint(workloadAuthorization);
        this.snapshotIds = endpoint(snapshotIds);
        this.trustedTime = endpoint(trustedTime);
        this.json = java.util.Objects.requireNonNull(json);
        this.exchange = java.util.Objects.requireNonNull(exchange);
    }

    @Override
    public DataBatchAuthorizationDecision authorize(DataBatchAuthorizationRequest request) {
        java.util.Objects.requireNonNull(request);
        ObjectNode body = operation("authorize-data-batch");
        body.put("tenantId", request.context().tenantId());
        body.put("principalRef", request.context().actorRef());
        body.put("commandType", request.commandType().name());
        body.put("sourceId", request.sourceId());
        body.put("batchId", request.batchId().toString());
        body.put("aggregateVersion", request.aggregateVersion());
        body.put("traceId", request.context().traceId());
        JsonNode response = response(dataBatchAuthorization, body, Set.of("decision"));
        return switch (text(response, "decision")) {
            case "ALLOW" -> DataBatchAuthorizationDecision.ALLOW;
            case "DENY" -> DataBatchAuthorizationDecision.DENY;
            case "DEPENDENCY_UNAVAILABLE" -> DataBatchAuthorizationDecision.DEPENDENCY_UNAVAILABLE;
            default -> throw unavailable();
        };
    }

    @Override
    public DataBatchWorkloadAuthorizationResult capture(
            DataBatchWorkloadAuthorizationRequest request) {
        return workload("capture-workload", null, request);
    }

    @Override
    public DataBatchWorkloadAuthorizationResult revalidate(
            DataBatchWorkloadAuthorizationEvidence captured,
            DataBatchWorkloadAuthorizationRequest request) {
        java.util.Objects.requireNonNull(captured);
        return workload("revalidate-workload", captured, request);
    }

    @Override
    public UUID nextId(Instant evaluatedAt) {
        java.util.Objects.requireNonNull(evaluatedAt);
        ObjectNode body = operation("next-quality-snapshot-id");
        body.put("evaluatedAt", evaluatedAt.toString());
        JsonNode response = response(snapshotIds, body, Set.of("snapshotId"));
        try {
            UUID value = UUID.fromString(text(response, "snapshotId"));
            if (value.version() != 7 || value.variant() != 2) throw unavailable();
            return value;
        } catch (IllegalArgumentException invalid) {
            throw unavailable();
        }
    }

    @Override
    public Optional<TimeSynchronizationStatus> current() {
        JsonNode response = response(trustedTime, operation("trusted-time-status"), Set.of(
                "sourceId", "profileVersion", "offsetMs", "observedAt", "freshUntil",
                "evidenceRef"));
        try {
            JsonNode offset = response.get("offsetMs");
            if (offset == null || !offset.isIntegralNumber() || !offset.canConvertToInt()) {
                throw unavailable();
            }
            return Optional.of(new TimeSynchronizationStatus(
                    text(response, "sourceId"), text(response, "profileVersion"),
                    offset.asInt(), instant(response, "observedAt"),
                    instant(response, "freshUntil"), text(response, "evidenceRef")));
        } catch (RuntimeException invalid) {
            throw unavailable();
        }
    }

    private DataBatchWorkloadAuthorizationResult workload(
            String operation,
            DataBatchWorkloadAuthorizationEvidence captured,
            DataBatchWorkloadAuthorizationRequest request) {
        java.util.Objects.requireNonNull(request);
        ObjectNode body = operation(operation);
        body.put("commandType", request.commandType().name());
        body.put("audience", request.audience());
        var capabilities = body.putArray("capabilities");
        request.capabilities().stream().sorted().forEach(capabilities::add);
        body.put("currentTime", request.currentTime().toString());
        if (captured != null) body.set("capturedEvidence", evidence(captured));
        JsonNode response = response(workloadAuthorization, body, Set.of(
                "status", "currentAuthorizationGeneration", "evidence"));
        String status = text(response, "status");
        JsonNode generation = response.get("currentAuthorizationGeneration");
        if (generation == null || !generation.isIntegralNumber()) throw unavailable();
        long currentGeneration = generation.asLong();
        return switch (status) {
            case "ALLOW" -> DataBatchWorkloadAuthorizationResult.allow(
                    parseEvidence(response.get("evidence")), currentGeneration);
            case "DENY" -> {
                requireNull(response, "evidence");
                yield DataBatchWorkloadAuthorizationResult.deny(currentGeneration);
            }
            case "DEPENDENCY_UNAVAILABLE" -> {
                requireNull(response, "evidence");
                if (currentGeneration != -1) throw unavailable();
                yield DataBatchWorkloadAuthorizationResult.dependencyUnavailable();
            }
            default -> throw unavailable();
        };
    }

    private DataBatchWorkloadAuthorizationEvidence parseEvidence(JsonNode value) {
        requireExact(value, Set.of(
                "environment", "principalRef", "mtlsSanUriRef", "audience", "capabilities",
                "authorizationGeneration", "policyVersion", "policyDigest", "effectiveAt",
                "expiresAt", "revokedAt"));
        JsonNode capabilities = value.get("capabilities");
        JsonNode generation = value.get("authorizationGeneration");
        if (capabilities == null || !capabilities.isArray() || capabilities.isEmpty()
                || generation == null || !generation.isIntegralNumber()) throw unavailable();
        LinkedHashSet<String> parsedCapabilities = new LinkedHashSet<>();
        capabilities.forEach(item -> parsedCapabilities.add(requiredText(item)));
        JsonNode revoked = value.get("revokedAt");
        Instant revokedAt = revoked == null || revoked.isNull()
                ? null : parseInstant(requiredText(revoked));
        try {
            return new DataBatchWorkloadAuthorizationEvidence(
                    text(value, "environment"), text(value, "principalRef"),
                    text(value, "mtlsSanUriRef"), text(value, "audience"),
                    parsedCapabilities, generation.asLong(), text(value, "policyVersion"),
                    text(value, "policyDigest"), instant(value, "effectiveAt"),
                    instant(value, "expiresAt"), revokedAt);
        } catch (RuntimeException invalid) {
            throw unavailable();
        }
    }

    private ObjectNode evidence(DataBatchWorkloadAuthorizationEvidence value) {
        ObjectNode node = json.createObjectNode();
        node.put("environment", value.environment());
        node.put("principalRef", value.principalRef());
        node.put("mtlsSanUriRef", value.mtlsSanUriRef());
        node.put("audience", value.audience());
        var capabilities = node.putArray("capabilities");
        value.capabilities().stream().sorted().forEach(capabilities::add);
        node.put("authorizationGeneration", value.authorizationGeneration());
        node.put("policyVersion", value.policyVersion());
        node.put("policyDigest", value.policyDigest());
        node.put("effectiveAt", value.effectiveAt().toString());
        node.put("expiresAt", value.expiresAt().toString());
        if (value.revokedAt() == null) node.putNull("revokedAt");
        else node.put("revokedAt", value.revokedAt().toString());
        return node;
    }

    private ObjectNode operation(String value) {
        ObjectNode body = json.createObjectNode();
        body.put("operation", value);
        return body;
    }

    private JsonNode response(URI endpoint, JsonNode request, Set<String> fields) {
        JsonNode value;
        try {
            value = exchange.exchange(endpoint, request);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
        requireExact(value, fields);
        return value;
    }

    private static void requireExact(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject() || !value.propertyNames().equals(fields)) {
            throw unavailable();
        }
    }

    private static void requireNull(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isNull()) throw unavailable();
    }

    private static String text(JsonNode value, String field) {
        return requiredText(value.get(field));
    }

    private static String requiredText(JsonNode value) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw unavailable();
        return value.asText();
    }

    private static Instant instant(JsonNode value, String field) {
        return parseInstant(text(value, field));
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException invalid) {
            throw unavailable();
        }
    }

    private static URI endpoint(URI value) {
        java.util.Objects.requireNonNull(value);
        if (!"https".equals(value.getScheme()) || value.getHost() == null
                || value.getUserInfo() != null || value.getFragment() != null
                || value.getPort() != -1) throw unavailable();
        return value;
    }

    private static Exchange httpExchange(HttpClient http, ObjectMapper json) {
        java.util.Objects.requireNonNull(json);
        return (endpoint, request) -> {
            try {
                byte[] payload = json.writeValueAsBytes(request);
                HttpRequest outbound = HttpRequest.newBuilder(endpoint)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                        .build();
                HttpResponse<byte[]> response = http.send(
                        outbound, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200 || response.body() == null
                        || response.body().length == 0
                        || response.body().length > MAX_RESPONSE_BYTES) throw unavailable();
                return json.readTree(response.body());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw unavailable();
            } catch (Exception failure) {
                throw unavailable();
            }
        };
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("INGESTION_QUALITY_PROVIDER_UNAVAILABLE");
    }

    @FunctionalInterface
    interface Exchange {
        JsonNode exchange(URI endpoint, JsonNode request);
    }
}
