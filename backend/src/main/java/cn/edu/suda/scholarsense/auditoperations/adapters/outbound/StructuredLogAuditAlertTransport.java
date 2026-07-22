package cn.edu.suda.scholarsense.auditoperations.adapters.outbound;

import cn.edu.suda.scholarsense.auditoperations.application.AuditAlertTransport;
import java.util.Objects;
import java.util.function.Consumer;

/** Production alert channel for the controlled structured-log binding. */
public final class StructuredLogAuditAlertTransport implements AuditAlertTransport {
    private static final System.Logger LOGGER =
            System.getLogger("cn.edu.suda.scholarsense.audit.alert");

    private final Consumer<String> channel;

    public StructuredLogAuditAlertTransport() {
        this(payload -> LOGGER.log(System.Logger.Level.WARNING, payload));
    }

    StructuredLogAuditAlertTransport(Consumer<String> channel) {
        this.channel = Objects.requireNonNull(channel);
    }

    @Override
    public void send(String safePayload) {
        if (safePayload == null || safePayload.isBlank()) {
            throw new IllegalArgumentException("AUDIT_ALERT_PAYLOAD_INVALID");
        }
        channel.accept(safePayload);
    }
}
