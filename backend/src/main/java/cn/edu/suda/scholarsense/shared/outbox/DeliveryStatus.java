package cn.edu.suda.scholarsense.shared.outbox;

/** Transport confirmation state; never a source-domain or business-apply state. */
public enum DeliveryStatus {
    PENDING("pending"),
    RETRYING("retrying"),
    CONFIRMED("confirmed"),
    FAILED("failed");

    private final String wireValue;

    DeliveryStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
