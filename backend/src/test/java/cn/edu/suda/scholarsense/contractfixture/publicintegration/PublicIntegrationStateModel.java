package cn.edu.suda.scholarsense.contractfixture.publicintegration;

import cn.edu.suda.scholarsense.shared.outbox.DeliveryRecordKey;
import cn.edu.suda.scholarsense.shared.outbox.DeliveryStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Test-scope executable semantics for PIC ordering and delivery ownership. */
final class PublicIntegrationStateModel {

    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private final DeliveryRecordKey key;
    private final CurrentDelivery current;
    private final long lastAllocatedSequence;
    private final Set<String> sealedGenerationKeys;
    private final List<Transition> transitionLedger;

    private PublicIntegrationStateModel(
            DeliveryRecordKey key,
            CurrentDelivery current,
            long lastAllocatedSequence,
            Set<String> sealedGenerationKeys,
            List<Transition> transitionLedger) {
        this.key = Objects.requireNonNull(key, "key");
        this.current = current;
        this.lastAllocatedSequence = lastAllocatedSequence;
        this.sealedGenerationKeys = Set.copyOf(sealedGenerationKeys);
        this.transitionLedger = List.copyOf(transitionLedger);
    }

    static PublicIntegrationStateModel empty(DeliveryRecordKey key) {
        return new PublicIntegrationStateModel(key, null, 0, Set.of(), List.of());
    }

    PublicIntegrationStateModel activate(QueuedDelivery queued) {
        requireSameKey(queued.key());
        if (current != null
                && current.status() != DeliveryStatus.CONFIRMED
                && current.status() != DeliveryStatus.FAILED) {
            throw new IllegalStateException("PIC_ACTIVE_GENERATION_EXISTS");
        }
        if (lastAllocatedSequence == MAX_SAFE_INTEGER) {
            throw new IllegalStateException("PIC_DELIVERY_SEQUENCE_EXHAUSTED");
        }
        if (sealedGenerationKeys.contains(queued.generationKey())) {
            throw new IllegalStateException("PIC_GENERATION_SEALED");
        }
        long sequence = lastAllocatedSequence + 1;
        long fencingToken = current == null ? 1 : current.fencingToken() + 1;
        var sealed = new LinkedHashSet<>(sealedGenerationKeys);
        if (current != null) {
            sealed.add(current.generationKey());
        }
        var next = new CurrentDelivery(
                key,
                queued.generationKey(),
                sequence,
                queued.provenance().sourceAggregateVersion(),
                DeliveryStatus.PENDING,
                fencingToken,
                false);
        var transitions = append(
                transitionLedger,
                new Transition(queued.generationKey(), sequence, null,
                        DeliveryStatus.PENDING, fencingToken, "activate"));
        return new PublicIntegrationStateModel(
                key, next, sequence, sealed, transitions);
    }

    PublicIntegrationStateModel confirm(String generationKey, long fencingToken) {
        var matched = requireCurrent(generationKey, fencingToken);
        if (matched.status() != DeliveryStatus.PENDING
                && matched.status() != DeliveryStatus.RETRYING) {
            throw new IllegalStateException("PIC_CONFIRM_STATE_INVALID");
        }
        var next = matched.withStatus(DeliveryStatus.CONFIRMED);
        return withTransition(next, matched.status(), "confirm");
    }

    PublicIntegrationStateModel retry(
            String generationKey,
            long fencingToken,
            boolean remediationAuthorized) {
        var matched = requireCurrent(generationKey, fencingToken);
        if (matched.sealed()
                || matched.status() != DeliveryStatus.FAILED
                || !remediationAuthorized) {
            throw new IllegalStateException("PIC_RETRY_NOT_AUTHORIZED");
        }
        var next = new CurrentDelivery(
                matched.key(), matched.generationKey(), matched.deliverySequence(),
                matched.sourceAggregateVersion(), DeliveryStatus.RETRYING,
                matched.fencingToken() + 1, false);
        return withTransition(next, matched.status(), "retry-authorized");
    }

    CurrentDelivery current() {
        if (current == null) {
            throw new IllegalStateException("PIC_CURRENT_MISSING");
        }
        return current;
    }

    List<Transition> transitionLedger() {
        return transitionLedger;
    }

    boolean isSealed(String generationKey) {
        return sealedGenerationKeys.contains(generationKey);
    }

    private PublicIntegrationStateModel withTransition(
            CurrentDelivery next,
            DeliveryStatus from,
            String reason) {
        var transitions = append(
                transitionLedger,
                new Transition(next.generationKey(), next.deliverySequence(), from,
                        next.status(), next.fencingToken(), reason));
        return new PublicIntegrationStateModel(
                key, next, lastAllocatedSequence, sealedGenerationKeys, transitions);
    }

    private CurrentDelivery requireCurrent(String generationKey, long fencingToken) {
        if (current == null
                || !current.generationKey().equals(generationKey)
                || current.fencingToken() != fencingToken
                || sealedGenerationKeys.contains(generationKey)) {
            throw new IllegalStateException("PIC_STALE_FENCE");
        }
        return current;
    }

    private void requireSameKey(DeliveryRecordKey candidate) {
        if (!key.equals(candidate)) {
            throw new IllegalArgumentException("PIC_DELIVERY_KEY_MISMATCH");
        }
    }

    private static List<Transition> append(
            List<Transition> source,
            Transition transition) {
        var result = new ArrayList<>(source);
        result.add(transition);
        return result;
    }

    static String generationKeyForEvent(String source, String sourceFactId) {
        requireText(source, "source");
        requireText(sourceFactId, "sourceFactId");
        return derived("g1", "{\"kind\":\"event\",\"source\":\""
                + escape(source) + "\",\"sourceFactId\":\""
                + escape(sourceFactId) + "\"}");
    }

    static String generationKeyForIntent(String deliveryIntentId) {
        requireText(deliveryIntentId, "deliveryIntentId");
        return derived("g1", "{\"deliveryIntentId\":\""
                + escape(deliveryIntentId) + "\",\"kind\":\"intent\"}");
    }

    static String deliveryIntentId(
            String tokenizedWorkItemKey,
            String notificationType,
            long escalationVersion) {
        requireText(tokenizedWorkItemKey, "tokenizedWorkItemKey");
        requireText(notificationType, "notificationType");
        requireSafePositive(escalationVersion, "escalationVersion");
        return derived("di1", "{\"escalationVersion\":" + escalationVersion
                + ",\"kind\":\"message-intent\",\"notificationType\":\""
                + escape(notificationType) + "\",\"workItemKeyToken\":\""
                + escape(tokenizedWorkItemKey) + "\"}");
    }

    static RouteDecision routeDecision(
            long currentRouteWatermark,
            long incomingRouteSequence,
            Long lastSourceAggregateVersion,
            long incomingSourceAggregateVersion,
            boolean sameIdentity) {
        if (currentRouteWatermark < 0
                || currentRouteWatermark > MAX_SAFE_INTEGER
                || incomingRouteSequence < 1
                || incomingRouteSequence > MAX_SAFE_INTEGER
                || incomingSourceAggregateVersion < 1
                || incomingSourceAggregateVersion > MAX_SAFE_INTEGER) {
            return RouteDecision.INVALID;
        }
        if (incomingRouteSequence == currentRouteWatermark) {
            return sameIdentity ? RouteDecision.DUPLICATE : RouteDecision.CONFLICT;
        }
        if (incomingRouteSequence < currentRouteWatermark) {
            return sameIdentity ? RouteDecision.STALE : RouteDecision.CONFLICT;
        }
        if (incomingRouteSequence > currentRouteWatermark + 1) {
            return RouteDecision.GAP;
        }
        if (lastSourceAggregateVersion != null
                && incomingSourceAggregateVersion <= lastSourceAggregateVersion) {
            return RouteDecision.SOURCE_VERSION_CONFLICT;
        }
        return RouteDecision.NEXT;
    }

    static boolean isRetryable(String result) {
        return switch (result) {
            case "timeout", "connect", "http-429", "http-5xx" -> true;
            default -> false;
        };
    }

    private static String derived(String prefix, String canonicalJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return prefix + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static long requireSafePositive(long value, String field) {
        if (value < 1 || value > MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(field + " outside JSON safe range");
        }
        return value;
    }

    enum ProvenanceMode {
        AGGREGATE_STREAM,
        INTENT_COMMAND
    }

    enum RouteDecision {
        NEXT,
        DUPLICATE,
        STALE,
        CONFLICT,
        GAP,
        SOURCE_VERSION_CONFLICT,
        INVALID
    }

    record Provenance(
            ProvenanceMode mode,
            String sourceEventId,
            String source,
            String sourceFactId,
            String sourceFactDigest,
            String eventPayloadDigest,
            String channelId,
            Long routeSequence,
            String deliveryIntentId,
            String requestDigest,
            long sourceAggregateVersion) {

        Provenance {
            Objects.requireNonNull(mode, "mode");
            requireSafePositive(sourceAggregateVersion, "sourceAggregateVersion");
            boolean hasStream = sourceEventId != null
                    || source != null
                    || sourceFactId != null
                    || sourceFactDigest != null
                    || eventPayloadDigest != null
                    || channelId != null
                    || routeSequence != null;
            boolean completeStream = sourceEventId != null
                    && source != null
                    && sourceFactId != null
                    && sourceFactDigest != null
                    && eventPayloadDigest != null
                    && channelId != null
                    && routeSequence != null;
            boolean hasIntent = deliveryIntentId != null || requestDigest != null;
            boolean completeIntent = deliveryIntentId != null && requestDigest != null;
            if (mode == ProvenanceMode.AGGREGATE_STREAM) {
                if (!completeStream || hasIntent) {
                    throw new IllegalArgumentException("PIC_PROVENANCE_MODE_INVALID");
                }
                requireSafePositive(routeSequence, "routeSequence");
            } else if (!completeIntent || hasStream) {
                throw new IllegalArgumentException("PIC_PROVENANCE_MODE_INVALID");
            }
        }

        static Provenance stream(
                String sourceEventId,
                String sourceFactId,
                String sourceFactDigest,
                String eventPayloadDigest,
                String channelId,
                long routeSequence,
                long sourceAggregateVersion) {
            return new Provenance(
                    ProvenanceMode.AGGREGATE_STREAM,
                    sourceEventId,
                    "urn:scholarsense:synthetic",
                    sourceFactId,
                    sourceFactDigest,
                    eventPayloadDigest,
                    channelId,
                    routeSequence,
                    null,
                    null,
                    sourceAggregateVersion);
        }

        static Provenance intent(
                String deliveryIntentId,
                String requestDigest,
                long sourceAggregateVersion) {
            return new Provenance(
                    ProvenanceMode.INTENT_COMMAND,
                    null, null, null, null, null, null, null,
                    deliveryIntentId,
                    requestDigest,
                    sourceAggregateVersion);
        }
    }

    record QueuedDelivery(
            DeliveryRecordKey key,
            String generationKey,
            String operation,
            int priority,
            Instant acceptedAt,
            Provenance provenance) {

        QueuedDelivery {
            Objects.requireNonNull(key, "key");
            requireText(generationKey, "generationKey");
            requireText(operation, "operation");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            Objects.requireNonNull(provenance, "provenance");
        }
    }

    record CurrentDelivery(
            DeliveryRecordKey key,
            String generationKey,
            long deliverySequence,
            long sourceAggregateVersion,
            DeliveryStatus status,
            long fencingToken,
            boolean sealed) {

        CurrentDelivery {
            Objects.requireNonNull(key, "key");
            requireText(generationKey, "generationKey");
            requireSafePositive(deliverySequence, "deliverySequence");
            requireSafePositive(sourceAggregateVersion, "sourceAggregateVersion");
            Objects.requireNonNull(status, "status");
            requireSafePositive(fencingToken, "fencingToken");
        }

        CurrentDelivery withStatus(DeliveryStatus next) {
            return new CurrentDelivery(
                    key, generationKey, deliverySequence,
                    sourceAggregateVersion, next, fencingToken, sealed);
        }
    }

    record Transition(
            String generationKey,
            long deliverySequence,
            DeliveryStatus from,
            DeliveryStatus to,
            long fencingToken,
            String reason) {
    }
}
