package cn.edu.suda.scholarsense.identityaccess.adapters.outbound;

import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationStorePort;
import cn.edu.suda.scholarsense.identityaccess.application.AccessInvalidationTransportPort;
import cn.edu.suda.scholarsense.identityaccess.domain.AccessInvalidationConsumerRoute;
import cn.edu.suda.scholarsense.shared.time.TrustedTimeSource;
import java.util.UUID;

/**
 * Current-release local transport. The relay sees only the transport port;
 * the consumer advances its own watermark in its own transaction.
 */
public final class LocalAccessInvalidationTransportAdapter
        implements AccessInvalidationTransportPort {
    private final AccessInvalidationStorePort store;
    private final JdbcAccessInvalidationConsumer consumer;
    private final TrustedTimeSource time;

    public LocalAccessInvalidationTransportAdapter(
            AccessInvalidationStorePort store,
            JdbcAccessInvalidationConsumer consumer,
            TrustedTimeSource time) {
        this.store = store;
        this.consumer = consumer;
        this.time = time;
    }

    @Override
    public void publish(
            UUID eventId,
            String eventType,
            String eventPayload,
            String payloadDigest,
            String deliveryKey,
            String traceId) {
        var fact = store.find(eventId)
                .orElseThrow(() -> new IllegalStateException(
                        "ACCESS_INVALIDATION_TRANSPORT_FACT_MISSING"));
        if (!"scholarsense.identity-access.responsibility.changed.v1"
                        .equals(eventType)
                || !fact.traceId().equals(traceId)
                || !("identity-access|" + eventId)
                        .equals(deliveryKey)) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_TRANSPORT_ENVELOPE_INVALID");
        }
        var decision = consumer.apply(
                new AccessInvalidationConsumerRoute(
                        "authorization-current-scope",
                        "identity-access",
                        fact.aggregateType(),
                        fact.lineageId()),
                fact,
                eventPayload,
                payloadDigest,
                time.now().instant());
        if (decision
                        == cn.edu.suda.scholarsense.identityaccess.domain
                                .AccessInvalidationDeliveryDecision
                                .GAP_BACKFILL_REQUIRED
                || decision
                        == cn.edu.suda.scholarsense.identityaccess.domain
                                .AccessInvalidationDeliveryDecision
                                .CONFLICT) {
            throw new IllegalStateException(
                    "ACCESS_INVALIDATION_CONSUMER_"
                            + decision.name());
        }
    }
}
