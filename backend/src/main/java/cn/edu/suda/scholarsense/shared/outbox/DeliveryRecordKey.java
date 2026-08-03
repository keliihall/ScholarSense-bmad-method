package cn.edu.suda.scholarsense.shared.outbox;

/**
 * Transport-neutral ownership key for one current external delivery lane.
 *
 * <p>The key deliberately contains no provider endpoint, external identifier,
 * persistence concern, transition guard, or source-domain status.</p>
 */
public record DeliveryRecordKey(
        String aggregateType,
        String aggregateId,
        String channelId,
        String contractVersion) {

    public DeliveryRecordKey {
        aggregateType = requireText(aggregateType, "aggregateType");
        aggregateId = requireText(aggregateId, "aggregateId");
        channelId = requireText(channelId, "channelId");
        contractVersion = requireText(contractVersion, "contractVersion");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
