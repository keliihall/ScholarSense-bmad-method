package cn.edu.suda.scholarsense.ingestionquality.application;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** A principal-free request sent to the authoritative workload identity boundary. */
public record DataBatchWorkloadAuthorizationRequest(
        DataBatchCommandType commandType,
        String audience,
        Set<String> capabilities,
        Instant currentTime) {
    public DataBatchWorkloadAuthorizationRequest {
        Objects.requireNonNull(commandType, "commandType");
        audience = DataBatchCommandRules.text(audience, 256);
        Objects.requireNonNull(capabilities, "capabilities");
        if (capabilities.isEmpty() || capabilities.size() > 16) {
            throw new IllegalArgumentException("INGESTION_QUALITY_REQUEST_INVALID");
        }
        LinkedHashSet<String> validated = new LinkedHashSet<>();
        for (String capability : capabilities) {
            validated.add(DataBatchCommandRules.text(capability, 128));
        }
        capabilities = Set.copyOf(validated);
        Objects.requireNonNull(currentTime, "currentTime");
    }
}
