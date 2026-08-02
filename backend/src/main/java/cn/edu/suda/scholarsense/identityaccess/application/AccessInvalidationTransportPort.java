package cn.edu.suda.scholarsense.identityaccess.application;

import java.util.UUID;

@FunctionalInterface
public interface AccessInvalidationTransportPort {
    void publish(
            UUID eventId,
            String eventType,
            String eventPayload,
            String payloadDigest,
            String deliveryKey,
            String traceId);
}
