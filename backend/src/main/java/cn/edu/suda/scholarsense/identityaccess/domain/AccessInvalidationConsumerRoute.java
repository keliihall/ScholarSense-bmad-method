package cn.edu.suda.scholarsense.identityaccess.domain;

public record AccessInvalidationConsumerRoute(
        String consumerId,
        String producer,
        AccessInvalidationAggregateType aggregateType,
        AccessInvalidationLineageId lineageId) {
    public AccessInvalidationConsumerRoute {
        AccessInvalidationValidation.identifier(
                consumerId, "ACCESS_INVALIDATION_CONSUMER_ID");
        if (!"identity-access".equals(producer)) {
            throw new IllegalArgumentException(
                    "ACCESS_INVALIDATION_PRODUCER_INVALID");
        }
        AccessInvalidationValidation.required(
                aggregateType, "aggregateType");
        AccessInvalidationValidation.required(lineageId, "lineageId");
    }
}
